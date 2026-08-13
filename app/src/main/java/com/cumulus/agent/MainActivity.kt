package com.cumulus.agent

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var llamaEngine: LlamaEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate()

        checkPermissions()

        webView = WebView(this)
        setContentView(webView)

        llamaEngine = LlamaEngine(this)

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = WebViewClient()

        webView.addJavascriptInterface(WebBridge(this, webView, llamaEngine), "CumulusNative")
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
    }
}
