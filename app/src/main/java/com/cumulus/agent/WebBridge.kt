package com.cumulus.agent

import android.content.Intent
import android.os.Build
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File

class WebBridge(
    private val activity: MainActivity,
    private val webView: WebView,
    private val llamaEngine: LlamaEngine
) {

    @JavascriptInterface
    fun sendPromptToGGUF(prompt: String) {
        val modelFile = File(activity.getExternalFilesDir(null), "qwen2.5-0.5b-instruct-q4_k_m.gguf")

        llamaEngine.processPrompt(prompt, modelFile.absolutePath) { response ->
            activity.runOnUiThread {
                val cleanResponse = response.replace("'", "\\'").replace("\n", "\\n")
                webView.evaluateJavascript("window.onAIResponse('$cleanResponse');", null)
            }
        }
    }

    @JavascriptInterface
    fun toggleBubbleService(enable: Boolean) {
        activity.runOnUiThread {
            val intent = Intent(activity, FloatingAgentService::class.java)
            if (enable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    activity.startForegroundService(intent)
                } else {
                    activity.startService(intent)
                }
            } else {
                activity.stopService(intent)
            }
        }
    }
}
