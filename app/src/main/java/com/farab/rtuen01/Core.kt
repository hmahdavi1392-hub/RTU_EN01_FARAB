package com.farab.rtuen01

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private val CLAMPS = linkedMapOf(
    "port" to Pair(1, 65535), "unit" to Pair(0, 255), "interval" to Pair(200, 60000),
    "di_n" to Pair(0, 256), "do_n" to Pair(0, 256),
    "rcr_start" to Pair(0, 399), "rcr_n" to Pair(0, 400),
    "di_base" to Pair(0, 65535), "do_base" to Pair(0, 65535), "rcr_base" to Pair(0, 65535)
)
private val DEFAULTS = mapOf(
    "port" to 502, "unit" to 1, "interval" to 500, "di_n" to 16, "do_n" to 16,
    "rcr_start" to 0, "rcr_n" to 16, "di_base" to 1024, "do_base" to 1280, "rcr_base" to 12288
)

fun cleanChannels(src: JSONArray?): JSONArray {
    val out = JSONArray()
    if (src == null) return out
    for (i in 0 until minOf(src.length(), 64)) {
        val o = src.optJSONObject(i) ?: continue
        var name = o.optString("name", "Analog")
        if (name.isEmpty()) name = "Analog"
        var scale = o.optDouble("scale", 1.0)
        if (scale.isNaN() || scale == 0.0) scale = 1.0
        var offset = o.optDouble("offset", 0.0)
        if (offset.isNaN()) offset = 0.0
        out.put(
            JSONObject()
                .put("name", name.take(40))
                .put("rcr", o.optInt("rcr", 0).coerceIn(0, 399))
                .put("unit", o.optString("unit", "").take(12))
                .put("scale", scale)
                .put("offset", offset)
                .put("signed", o.optBoolean("signed", false))
        )
    }
    return out
}

fun cleanCfg(item: JSONObject?): JSONObject {
    val o = JSONObject()
    var name = (item?.optString("name", "") ?: "").trim()
    if (name.isEmpty()) name = "RTU-EN01"
    var ip = (item?.optString("ip", "") ?: "").trim()
    if (ip.isEmpty()) ip = "192.168.0.20"
    o.put("name", name.take(40))
    o.put("ip", ip.take(64))
    for ((k, range) in CLAMPS) {
        val def = DEFAULTS[k] ?: 0
        val v = item?.optInt(k, def) ?: def
        o.put(k, v.coerceIn(range.first, range.second))
    }
    o.put("analog", cleanChannels(item?.optJSONArray("analog")))
    return o
}

private fun readCards(client: ModbusClient, c: JSONObject): JSONObject {
    val arr = c.getJSONArray("analog")
    val addrs = (0 until arr.length()).map { arr.getJSONObject(it).getInt("rcr") }.distinct().sorted()
    val out = JSONObject()
    if (addrs.isEmpty()) return out
    val base = c.getInt("rcr_base")
    val lo = addrs.first()
    val hi = addrs.last()
    if (hi - lo < 100) {
        val vals = client.readRegs(3, base + lo, hi - lo + 1)
        for (a in addrs) out.put(a.toString(), vals[a - lo])
    } else {
        for (a in addrs) out.put(a.toString(), client.readRegs(3, base + a, 1)[0])
    }
    return out
}

/** One RTU-EN01 unit with its own polling thread. */
class Device(val id: String, cfg0: JSONObject, private val isActive: () -> Boolean) {
    @Volatile
    var cfg: JSONObject = cfg0
        private set

    @Volatile
    var rev: Int = 1
        private set

    @Volatile
    var state: JSONObject? = null
        private set

    @Volatile
    private var stop = false
    private val sem = Semaphore(0)
    private val lock = Any()
    private var client: ModbusClient? = null
    private var clientKey: String? = null

    fun start() {
        val t = Thread { loop() }
        t.isDaemon = true
        t.start()
    }

    fun wake() {
        sem.release()
    }

    fun update(newCfg: JSONObject) {
        cfg = newCfg
        rev += 1
        state = null
        wake()
    }

    fun getClient(c: JSONObject): ModbusClient {
        synchronized(lock) {
            val key = c.getString("ip") + ":" + c.getInt("port") + ":" + c.getInt("unit")
            if (key != clientKey) {
                client?.close()
                client = ModbusClient(c.getString("ip"), c.getInt("port"), c.getInt("unit"))
                clientKey = key
            }
            return client!!
        }
    }

    fun close() {
        stop = true
        sem.release()
        synchronized(lock) { client?.close() }
    }

    private fun loop() {
        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
        while (!stop) {
            if (!isActive()) {
                sem.tryAcquire(500, TimeUnit.MILLISECONDS)
                sem.drainPermits()
                continue
            }
            val c = cfg
            val r = rev
            val errors = JSONObject()
            var di = JSONArray()
            var dOut = JSONArray()
            var rcr = JSONArray()
            var an = JSONObject()
            var abort = false
            val client = getClient(c)

            fun attempt(name: String, n: Int, fn: () -> Unit) {
                if (abort || n == 0) return
                try {
                    fn()
                } catch (e: Exception) {
                    val m = e.message ?: e.toString()
                    errors.put(name, m)
                    if (m.startsWith("No connection")) abort = true
                }
            }
            attempt("di", c.getInt("di_n")) {
                di = JSONArray(client.readBits(2, c.getInt("di_base"), c.getInt("di_n")).toList())
            }
            attempt("do", c.getInt("do_n")) {
                dOut = JSONArray(client.readBits(1, c.getInt("do_base"), c.getInt("do_n")).toList())
            }
            attempt("rcr", c.getInt("rcr_n")) {
                rcr = JSONArray(
                    client.readRegs(3, c.getInt("rcr_base") + c.getInt("rcr_start"), c.getInt("rcr_n")).toList()
                )
            }
            attempt("an", c.getJSONArray("analog").length()) { an = readCards(client, c) }

            val res = JSONObject()
                .put("di", di).put("do", dOut).put("rcr", rcr).put("an", an)
                .put("errors", errors).put("ok", errors.length() == 0)
                .put("time", timeFmt.format(Date())).put("rev", r)
            if (stop) break
            if (cfg === c) state = res
            sem.tryAcquire(c.getInt("interval").toLong(), TimeUnit.MILLISECONDS)
            sem.drainPermits()
        }
    }
}

/** Device list + the same JSON API the web version exposes (/api/devices, /api/state, /api/write). */
class Core(private val dir: File) {
    private val devs = LinkedHashMap<String, Device>()
    private var order: List<String> = emptyList()

    @Volatile
    private var active = true

    init {
        var items = JSONArray()
        try {
            items = JSONArray(File(dir, "devices.json").readText())
        } catch (e: Exception) {
        }
        if (items.length() == 0) {
            items.put(JSONObject().put("id", "").put("cfg", JSONObject()))
        }
        applyList(items)
    }

    fun setActive(on: Boolean) {
        active = on
        synchronized(this) { for (d in devs.values) d.wake() }
    }

    fun close() {
        synchronized(this) { for (d in devs.values) d.close() }
    }

    private fun save() {
        try {
            val arr = JSONArray()
            for (id in order) {
                val d = devs[id] ?: continue
                arr.put(JSONObject().put("id", id).put("cfg", d.cfg))
            }
            File(dir, "devices.json").writeText(arr.toString(2))
        } catch (e: Exception) {
        }
    }

    @Synchronized
    private fun applyList(items: JSONArray) {
        val newOrder = ArrayList<String>()
        val seen = HashSet<String>()
        val idPattern = Regex("[0-9a-f]{8}")
        for (i in 0 until minOf(items.length(), 32)) {
            val item = items.optJSONObject(i) ?: continue
            val cfg = cleanCfg(item.optJSONObject("cfg"))
            var did = item.optString("id", "")
            var dev: Device? = if (did !in seen) devs[did] else null
            if (dev == null) {
                if (!idPattern.matches(did) || did in seen || devs.containsKey(did)) {
                    did = UUID.randomUUID().toString().replace("-", "").take(8)
                }
            }
            seen.add(did)
            if (dev == null) {
                dev = Device(did, cfg) { active }
                devs[did] = dev
                dev.start()
            } else if (dev.cfg.toString() != cfg.toString()) {
                dev.update(cfg)
            }
            newOrder.add(did)
        }
        if (newOrder.isEmpty()) throw IllegalArgumentException("at least one device is required")
        for (k in devs.keys.toList()) {
            if (k !in newOrder) devs.remove(k)?.close()
        }
        order = newOrder
        save()
    }

    @Synchronized
    private fun listing(): JSONArray {
        val arr = JSONArray()
        for (id in order) {
            val d = devs[id] ?: continue
            arr.put(JSONObject().put("id", id).put("cfg", d.cfg).put("rev", d.rev))
        }
        return arr
    }

    @Synchronized
    private fun stateOf(id: String): String {
        val d = devs[id]
        val status = JSONObject()
        val revs = JSONObject()
        for ((k, v) in devs) {
            val st = v.state
            if (st == null) status.put(k, JSONObject.NULL) else status.put(k, st.optBoolean("ok"))
            revs.put(k, v.rev)
        }
        val cur: Any = d?.state ?: JSONObject.NULL
        return JSONObject().put("state", cur).put("rev", d?.rev ?: 0)
            .put("status", status).put("revs", revs).toString()
    }

    @Synchronized
    private fun device(id: String): Device? = devs[id]

    private fun write(d: JSONObject): String {
        val dev = device(d.optString("id", "")) ?: throw IllegalArgumentException("unknown device")
        val c = dev.getClient(dev.cfg)
        val addr = d.getInt("addr")
        if (addr !in 0..65535) throw IllegalArgumentException("address out of range")
        when (d.getString("type")) {
            "coil" -> c.writeCoil(addr, d.optBoolean("value"))
            "reg" -> {
                val v = d.getInt("value")
                if (v !in -32768..65535) {
                    throw IllegalArgumentException("value must be between -32768 and 65535")
                }
                c.writeReg(addr, v)
            }
            else -> throw IllegalArgumentException("bad type")
        }
        dev.wake()
        return JSONObject().put("ok", true).toString()
    }

    private fun param(query: String, key: String): String {
        for (part in query.split("&")) {
            val kv = part.split("=", limit = 2)
            if (kv.size == 2 && kv[0] == key) return kv[1]
        }
        return ""
    }

    /** Same routes as the Python server; returns a JSON string. */
    fun handle(method: String, fullPath: String, body: String): String {
        val q = fullPath.indexOf('?')
        val path = if (q >= 0) fullPath.substring(0, q) else fullPath
        val query = if (q >= 0) fullPath.substring(q + 1) else ""
        return when {
            path == "/api/devices" && method == "GET" ->
                JSONObject().put("devices", listing()).toString()
            path == "/api/devices" && method == "POST" -> {
                applyList(JSONArray(body))
                JSONObject().put("devices", listing()).toString()
            }
            path == "/api/state" -> stateOf(param(query, "id"))
            path == "/api/write" -> write(JSONObject(body))
            path == "/api/info" ->
                JSONObject().put("ip", "").put("port", 0).put("pin", "").put("local", true).toString()
            else -> JSONObject().put("ok", false).put("error", "not found").toString()
        }
    }
}
