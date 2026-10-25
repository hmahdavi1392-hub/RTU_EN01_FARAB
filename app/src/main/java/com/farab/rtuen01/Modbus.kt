package com.farab.rtuen01

import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer

class ModbusError(message: String) : Exception(message)

/** Minimal Modbus TCP client (FC01/02/03/05/06). One request at a time. */
class ModbusClient(
    private val ip: String,
    private val port: Int,
    private val unit: Int,
    private val timeoutMs: Int = 2000
) {
    private var sock: Socket? = null
    private var tid = 0

    @Synchronized
    fun close() {
        try {
            sock?.close()
        } catch (e: IOException) {
        }
        sock = null
    }

    private fun connect() {
        val s = Socket()
        s.connect(InetSocketAddress(ip, port), timeoutMs)
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        sock = s
    }

    private fun readFully(inp: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r < 0) throw IOException("connection closed")
            off += r
        }
        return buf
    }

    @Synchronized
    private fun txn(fc: Int, payload: ByteArray): ByteArray {
        var last: IOException? = null
        for (attempt in 0 until 2) { // one retry with a fresh connection
            try {
                if (sock == null) connect()
                tid = (tid + 1) and 0xFFFF
                val bb = ByteBuffer.allocate(8 + payload.size)
                bb.putShort(tid.toShort())
                bb.putShort(0)
                bb.putShort((payload.size + 2).toShort())
                bb.put(unit.toByte())
                bb.put(fc.toByte())
                bb.put(payload)
                val out = sock!!.getOutputStream()
                out.write(bb.array())
                out.flush()
                val inp = sock!!.getInputStream()
                val hdr = readFully(inp, 7)
                val length = ((hdr[4].toInt() and 0xFF) shl 8) or (hdr[5].toInt() and 0xFF)
                if (length < 2) throw IOException("bad response")
                val body = readFully(inp, length - 1)
                if ((body[0].toInt() and 0x80) != 0) {
                    val code = body[1].toInt() and 0xFF
                    val text = when (code) {
                        1 -> "illegal function"
                        2 -> "illegal address"
                        3 -> "illegal value"
                        4 -> "device failure"
                        else -> "?"
                    }
                    throw ModbusError("Modbus exception $code ($text)")
                }
                return body
            } catch (e: IOException) {
                last = e
                try {
                    sock?.close()
                } catch (e2: IOException) {
                }
                sock = null
            }
        }
        throw ModbusError("No connection: ${last?.message ?: "unknown"}")
    }

    private fun req(addr: Int, n: Int): ByteArray =
        ByteBuffer.allocate(4).putShort(addr.toShort()).putShort(n.toShort()).array()

    fun readBits(fc: Int, addr0: Int, count: Int): IntArray {
        val out = IntArray(count)
        var addr = addr0
        var done = 0
        while (done < count) {
            val n = minOf(count - done, 1000)
            val body = txn(fc, req(addr, n))
            if (body.size < 2 + (n + 7) / 8) throw ModbusError("short response")
            for (i in 0 until n) {
                out[done + i] = (body[2 + i / 8].toInt() shr (i % 8)) and 1
            }
            addr += n
            done += n
        }
        return out
    }

    fun readRegs(fc: Int, addr0: Int, count: Int): IntArray {
        val out = IntArray(count)
        var addr = addr0
        var done = 0
        while (done < count) {
            val n = minOf(count - done, 100)
            val body = txn(fc, req(addr, n))
            if (body.size < 2 + 2 * n) throw ModbusError("short response")
            for (i in 0 until n) {
                out[done + i] =
                    ((body[2 + 2 * i].toInt() and 0xFF) shl 8) or (body[3 + 2 * i].toInt() and 0xFF)
            }
            addr += n
            done += n
        }
        return out
    }

    fun writeCoil(addr: Int, value: Boolean) {
        txn(5, ByteBuffer.allocate(4).putShort(addr.toShort())
            .putShort(if (value) 0xFF00.toShort() else 0.toShort()).array())
    }

    fun writeReg(addr: Int, value: Int) {
        txn(6, ByteBuffer.allocate(4).putShort(addr.toShort())
            .putShort((value and 0xFFFF).toShort()).array())
    }
}
