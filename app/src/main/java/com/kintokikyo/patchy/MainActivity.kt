package com.kintokikyo.patchy

import android.app.Activity
import android.os.Bundle
import android.util.Log
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
    }

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // WebView
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

        // Load APK assets through a normal HTTPS origin.
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler(
                "/assets/",
                WebViewAssetLoader.AssetsPathHandler(this)
            )
            .build()

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                
                Log.d(
                    TAG, 
                    "Asset request: ${request.url}"
                )

                val response = assetLoader.shouldInterceptRequest(request.url)

                if (response != null) {
                    val headers =
                        response.responseHeaders?.toMutableMap()
                            ?: mutableMapOf()

                    // Required for Patchy's threaded WASM build.
                    headers["Cross-Origin-Opener-Policy"] =
                        "same-origin"

                    headers["Cross-Origin-Embedder-Policy"] =
                        "require-corp"

                    headers["Cross-Origin-Resource-Policy"] =
                        "same-origin"

                    // Required by Android WebView's
                    // cross-origin isolation allowlist.
                    headers["Document-Isolation-Policy"] =
                        "isolate-and-credentialless"

                    response.responseHeaders = headers
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
                    "WebView error: $errorCode $description URL=$failingUrl"
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                Log.e(
                    TAG,
                    "HTTP error: ${errorResponse.statusCode} ${request.url}"
                )
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onConsoleMessage(
                consoleMessage: android.webkit.ConsoleMessage
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

        setContentView(webView)

        configureCrossOriginIsolation()

        // Patchy is now loaded from APK assets,
        // not from localhost.
        webView.loadUrl(
            "$PATCHY_ORIGIN/assets/patchy/patchy.html?PATCHY_WASM_FORCE=st"
        )
    }

    private fun configureCrossOriginIsolation() {

        val isolationSupported =
            WebViewFeature.isFeatureSupported(
                WebViewFeature.CROSS_ORIGIN_ISOLATED_ALLOWLIST
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

        if (isolationSupported && multiProfileSupported) {

            WebViewCompat
                .getProfile(webView)
                .setCrossOriginIsolatedAllowlist(
                    setOf(PATCHY_ORIGIN)
                )

            Log.d(
                TAG,
                "Cross-Origin Isolation allowlist enabled for " +
                    PATCHY_ORIGIN
            )

        } else {

            Log.e(
                TAG,
                "Cross-Origin Isolation allowlist NOT supported"
            )
        }
    }

    override fun onDestroy() {

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
