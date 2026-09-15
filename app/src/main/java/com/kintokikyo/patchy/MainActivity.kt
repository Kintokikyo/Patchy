package com.kintokikyo.patchy

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

class MainActivity : Activity() {

    companion object {
        private const val TAG = "PatchyWebView"
        private const val PATCHY_ORIGIN =
            "https://appassets.androidplatform.net"

        private const val FILE_CHOOSER_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    // Callback untuk Android file picker
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ==========================================
        // LANDSCAPE
        // ==========================================

        requestedOrientation =
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // ==========================================
        // FULLSCREEN
        // Hilangkan status bar + navigation bar
        // ==========================================

        hideSystemBars()

        // ==========================================
        // WEBVIEW
        // ==========================================

        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true

            allowFileAccess = false
            allowContentAccess = false

            mediaPlaybackRequiresUserGesture = false

            builtInZoomControls = false
            displayZoomControls = false
        }

        // ==========================================
        // APK ASSET LOADER
        // ==========================================

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        // ==========================================
        // WEBVIEW CLIENT
        // ==========================================

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {

                Log.d(
                    TAG,
                    "Asset request: ${request.url}"
                )

                val response =
                    assetLoader.shouldInterceptRequest(
                        request.url
                    )

                if (response != null) {

                    val headers =
                        response.responseHeaders
                            ?.toMutableMap()
                            ?: mutableMapOf()

                    // Patchy WASM headers
                    headers["Cross-Origin-Opener-Policy"] =
                        "same-origin"

                    headers["Cross-Origin-Embedder-Policy"] =
                        "require-corp"

                    headers["Cross-Origin-Resource-Policy"] =
                        "same-origin"

                    // Android WebView isolation
                    headers["Document-Isolation-Policy"] =
                        "isolate-and-credentialless"

                    response.responseHeaders =
                        headers
                }

                return response
            }

            override fun onReceivedError(
                view: WebView,
                errorCode: Int,
                description: String,
                failingUrl: String
            ) {

                Log.e(
                    TAG,
                    "WebView error: " +
                        "$errorCode $description " +
                        "URL=$failingUrl"
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {

                Log.e(
                    TAG,
                    "HTTP error: " +
                        "${errorResponse.statusCode} " +
                        request.url
                )
            }
        }

        // ==========================================
        // WEB CHROME CLIENT
        // FILE PICKER + CONSOLE
        // ==========================================

        webView.webChromeClient =
            object : WebChromeClient() {

                // ----------------------------------
                // Android File Picker
                // ----------------------------------

                override fun onShowFileChooser(
                    webView: WebView,
                    filePathCallback:
                        ValueCallback<Array<Uri>>?,
                    fileChooserParams:
                        FileChooserParams?
                ): Boolean {

                    Log.d(
                        TAG,
                        "Opening Android file picker"
                    )

                    // Batalkan callback sebelumnya
                    this@MainActivity
                        .filePathCallback
                        ?.onReceiveValue(null)

                    this@MainActivity
                        .filePathCallback =
                        filePathCallback

                    val intent =
                        Intent(
                            Intent.ACTION_OPEN_DOCUMENT
                        ).apply {

                            addCategory(
                                Intent.CATEGORY_OPENABLE
                            )

                            // Patchy bisa membuka
                            // PNG, JPG, PSD, PSB, dll.
                            type = "*/*"

                            putExtra(
                                Intent.EXTRA_ALLOW_MULTIPLE,
                                true
                            )
                        }

                    startActivityForResult(
                        intent,
                        FILE_CHOOSER_REQUEST_CODE
                    )

                    return true
                }

                // ----------------------------------
                // JavaScript Console
                // ----------------------------------

                override fun onConsoleMessage(
                    consoleMessage: ConsoleMessage
                ): Boolean {

                    Log.d(
                        TAG,
                        "JS: ${consoleMessage.message()} " +
                            "(${consoleMessage.sourceId()}:" +
                            "${consoleMessage.lineNumber()})"
                    )

                    return true
                }
            }

        // ==========================================
        // TAMPILKAN WEBVIEW
        // ==========================================

        setContentView(webView)

        // ==========================================
        // CROSS ORIGIN ISOLATION
        // ==========================================

        configureCrossOriginIsolation()

        // ==========================================
        // LOAD PATCHY ST
        // ==========================================

        webView.loadUrl(
            "$PATCHY_ORIGIN/assets/patchy/patchy.html" +
                "?PATCHY_WASM_FORCE=st"
        )
    }

    // ==============================================
    // CROSS ORIGIN ISOLATION
    // ==============================================

    private fun configureCrossOriginIsolation() {

        val isolationSupported =
            WebViewFeature.isFeatureSupported(
                WebViewFeature
                    .CROSS_ORIGIN_ISOLATED_ALLOWLIST
            )

        val multiProfileSupported =
            WebViewFeature.isFeatureSupported(
                WebViewFeature.MULTI_PROFILE
            )

        Log.d(
            TAG,
            "CROSS_ORIGIN_ISOLATED_ALLOWLIST supported = " +
                isolationSupported
        )

        Log.d(
            TAG,
            "MULTI_PROFILE supported = " +
                multiProfileSupported
        )

        if (
            isolationSupported &&
            multiProfileSupported
        ) {

            WebViewCompat
                .getProfile(webView)
                .setCrossOriginIsolatedAllowlist(
                    setOf(PATCHY_ORIGIN)
                )

            Log.d(
                TAG,
                "Cross-Origin Isolation allowlist " +
                    "enabled for " +
                    PATCHY_ORIGIN
            )

        } else {

            Log.e(
                TAG,
                "Cross-Origin Isolation allowlist " +
                    "NOT supported"
            )
        }
    }

    // ==============================================
    // FULLSCREEN / SYSTEM BAR
    // ==============================================

    private fun hideSystemBars() {

        if (
            Build.VERSION.SDK_INT >=
            Build.VERSION_CODES.R
        ) {

            window.setDecorFitsSystemWindows(false)

            window.insetsController?.let { controller ->

                controller.hide(
                    android.view.WindowInsets.Type.statusBars() or
                        android.view.WindowInsets.Type.navigationBars()
                )

                controller.systemBarsBehavior =
                    android.view.WindowInsetsController
                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }

        } else {

            @Suppress("DEPRECATION")

            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
    }

    // ==============================================
    // PASTIKAN SYSTEM BAR TETAP TERSEMBUNYI
    // ==============================================

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(
            hasFocus
        )

        if (hasFocus) {
            hideSystemBars()
        }
    }

    // ==============================================
    // HASIL FILE PICKER
    // ==============================================

    @Suppress("DEPRECATION")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (
            requestCode !=
            FILE_CHOOSER_REQUEST_CODE
        ) {
            return
        }

        Log.d(
            TAG,
            "File picker result: $resultCode"
        )

        val callback =
            filePathCallback

        filePathCallback = null

        // User batal memilih file
        if (
            resultCode != RESULT_OK ||
            data == null
        ) {

            callback?.onReceiveValue(null)
            return
        }

        val uris =
            mutableListOf<Uri>()

        // Multiple file
        data.clipData?.let { clipData ->

            for (
                i in 0 until clipData.itemCount
            ) {

                uris.add(
                    clipData
                        .getItemAt(i)
                        .uri
                )
            }
        }

        // Single file
        if (uris.isEmpty()) {

            data.data?.let { uri ->

                uris.add(uri)
            }
        }

        Log.d(
            TAG,
            "Selected files: ${uris.size}"
        )

        callback?.onReceiveValue(
            uris.toTypedArray()
        )
    }

    // ==============================================
    // DESTROY
    // ==============================================

    override fun onDestroy() {

        // Bersihkan callback file picker
        filePathCallback
            ?.onReceiveValue(null)

        filePathCallback = null

        if (::webView.isInitialized) {

            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
