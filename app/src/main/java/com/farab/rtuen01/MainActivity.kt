package com.farab.rtuen01

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var core: Core
    private val pool = Executors.newCachedThreadPool()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        core = Core(filesDir)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.parseColor("#0B1220")
        window.navigationBarColor = Color.parseColor("#0B1220")

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#0F172A"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webViewClient = WebViewClient()
        web.webChromeClient = object : WebChromeClient() {
            // The page uses confirm(); WebView needs a native dialog for it.
            override fun onJsConfirm(
                view: WebView?, url: String?, message: String?, result: JsResult?
            ): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message ?: "")
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .show()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "AndroidBridge")
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    override fun onStart() {
        super.onStart()
        core.setActive(true)
    }

    override fun onStop() {
        core.setActive(false) // stop polling while the app is in the background
        super.onStop()
    }

    override fun onDestroy() {
        core.close()
        pool.shutdownNow()
        super.onDestroy()
    }

    /** JavaScript -> Kotlin. Runs the request off the UI thread and answers asynchronously. */
    inner class Bridge {
        @JavascriptInterface
        fun call(id: Int, method: String, path: String, body: String) {
            pool.execute {
                val out = try {
                    core.handle(method, path, body)
                } catch (e: Exception) {
                    JSONObject().put("ok", false).put("error", e.message ?: e.toString()).toString()
                }
                runOnUiThread {
                    web.evaluateJavascript(
                        "window.__bridgeResult($id, " + JSONObject.quote(out) + ")", null
                    )
                }
            }
        }
    }
}
