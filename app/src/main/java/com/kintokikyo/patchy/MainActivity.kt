package com.kintokikyo.patchy

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.OutputStream


class MainActivity : Activity() {

    companion object {

        private const val TAG =
            "PatchyWebView"

        private const val PATCHY_ORIGIN =
            "https://appassets.androidplatform.net"

        private const val FILE_CHOOSER_REQUEST_CODE =
            1001
    }


    private lateinit var webView: WebView

    private lateinit var assetLoader:
        WebViewAssetLoader


    // ==============================================
    // ANDROID FILE PICKER
    // ==============================================

    private var filePathCallback:
        ValueCallback<Array<Uri>>? = null


    // ==============================================
    // PATCHY SAVE
    // ==============================================

    private var saveOutputStream:
        OutputStream? = null

    private var saveUri:
        Uri? = null

    private val saveLock =
        Any()


    // ==============================================
    // ON CREATE
    // ==============================================

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {

        super.onCreate(
            savedInstanceState
        )


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

        webView =
            WebView(this)


        webView.settings.apply {

            javaScriptEnabled =
                true

            domStorageEnabled =
                true

            allowFileAccess =
                false

            allowContentAccess =
                false

            mediaPlaybackRequiresUserGesture =
                false

            builtInZoomControls =
                false

            displayZoomControls =
                false
        }


        // ==========================================
        // ANDROID SAVE BRIDGE
        // ==========================================

        webView.addJavascriptInterface(
            AndroidSaveBridge(),
            "PatchyAndroid"
        )


        // ==========================================
        // APK ASSET LOADER
        // ==========================================

        assetLoader =
            WebViewAssetLoader.Builder()
                .addPathHandler(
                    "/assets/",
                    WebViewAssetLoader
                        .AssetsPathHandler(this)
                )
                .build()


        // ==========================================
        // WEBVIEW CLIENT
        // ==========================================

        webView.webViewClient =
            object : WebViewClient() {


                // ----------------------------------
                // ASSET REQUEST
                // ----------------------------------

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest
                ): WebResourceResponse? {


                    Log.d(
                        TAG,
                        "Asset request: ${request.url}"
                    )


                    val response =
                        assetLoader
                            .shouldInterceptRequest(
                                request.url
                            )


                    if (response != null) {


                        val headers =
                            response
                                .responseHeaders
                                ?.toMutableMap()
                                ?: mutableMapOf()


                        // ----------------------------------
                        // Patchy WASM headers
                        // ----------------------------------

                        headers[
                            "Cross-Origin-Opener-Policy"
                        ] =
                            "same-origin"


                        headers[
                            "Cross-Origin-Embedder-Policy"
                        ] =
                            "require-corp"


                        headers[
                            "Cross-Origin-Resource-Policy"
                        ] =
                            "same-origin"


                        // ----------------------------------
                        // Android WebView isolation
                        // ----------------------------------

                        headers[
                            "Document-Isolation-Policy"
                        ] =
                            "isolate-and-credentialless"


                        response.responseHeaders =
                            headers
                    }


                    return response
                }


                // ----------------------------------
                // PAGE FINISHED
                // ----------------------------------

                override fun onPageFinished(
                    view: WebView,
                    url: String
                ) {

                    super.onPageFinished(
                        view,
                        url
                    )


                    Log.d(
                        TAG,
                        "Patchy page finished: $url"
                    )


                    // Pasang sistem Save Android
                    installPatchySaveBridge()
                }


                // ----------------------------------
                // WEBVIEW ERROR
                // ----------------------------------

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


                // ----------------------------------
                // HTTP ERROR
                // ----------------------------------

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
                            type =
                                "*/*"


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

        setContentView(
            webView
        )


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


    // ==================================================
    // ANDROID SAVE BRIDGE
    // ==================================================

    private inner class AndroidSaveBridge {


        // ----------------------------------------------
        // PREPARE SAVE
        // ----------------------------------------------

        @JavascriptInterface
        fun prepareSave(
            fileName: String,
            mimeType: String
        ) {


            try {


                // Android WebView memanggil
                // JavascriptInterface dari thread background.


                val safeFileName =
                    sanitizeFileName(
                        fileName
                    )


                val safeMimeType =
                    if (
                        mimeType.isNotBlank()
                    ) {
                        mimeType
                    } else {
                        "application/octet-stream"
                    }


                synchronized(
                    saveLock
                ) {


                    // Bersihkan save sebelumnya
                    try {

                        saveOutputStream
                            ?.close()

                    } catch (
                        _: Exception
                    ) {
                    }


                    saveOutputStream =
                        null

                    saveUri =
                        null


                    // ----------------------------------
                    // Buat file di:
                    //
                    // Download/Patchy/
                    // ----------------------------------

                    val values =
                        ContentValues().apply {


                            put(
                                MediaStore.Downloads
                                    .DISPLAY_NAME,
                                safeFileName
                            )


                            put(
                                MediaStore.Downloads
                                    .MIME_TYPE,
                                safeMimeType
                            )


                            put(
                                MediaStore.Downloads
                                    .RELATIVE_PATH,
                                "Download/Patchy"
                            )


                            // File masih sedang ditulis.
                            put(
                                MediaStore.Downloads
                                    .IS_PENDING,
                                1
                            )
                        }


                    val uri =
                        contentResolver.insert(
                            MediaStore.Downloads
                                .EXTERNAL_CONTENT_URI,
                            values
                        )


                    if (
                        uri == null
                    ) {

                        throw IllegalStateException(
                            "MediaStore gagal membuat file"
                        )
                    }


                    val output =
                        contentResolver
                            .openOutputStream(
                                uri
                            )


                    if (
                        output == null
                    ) {


                        contentResolver.delete(
                            uri,
                            null,
                            null
                        )


                        throw IllegalStateException(
                            "Tidak bisa membuka OutputStream"
                        )
                    }


                    saveUri =
                        uri

                    saveOutputStream =
                        output


                    Log.d(
                        TAG,
                        "Save started: $uri"
                    )
                }


                // Beritahu JavaScript bahwa
                // Android sudah siap menerima data.
                runOnUiThread {


                    webView.evaluateJavascript(
                        """
                        window.__patchySaveReady = true;
                        window.__patchySaveCancelled = false;
                        window.__patchySaveError = false;
                        """.trimIndent(),
                        null
                    )
                }


            } catch (
                e: Exception
            ) {


                Log.e(
                    TAG,
                    "Failed preparing save",
                    e
                )


                runOnUiThread {


                    webView.evaluateJavascript(
                        """
                        window.__patchySaveReady = false;
                        window.__patchySaveError = true;
                        """.trimIndent(),
                        null
                    )
                }
            }
        }


        // ----------------------------------------------
        // WRITE CHUNK
        // ----------------------------------------------

        @JavascriptInterface
        fun writeChunk(
            base64Data: String
        ) {


            try {


                val bytes =
                    Base64.decode(
                        base64Data,
                        Base64.DEFAULT
                    )


                synchronized(
                    saveLock
                ) {


                    val output =
                        saveOutputStream
                            ?: throw IllegalStateException(
                                "OutputStream tidak tersedia"
                            )


                    output.write(
                        bytes
                    )
                }


            } catch (
                e: Exception
            ) {


                Log.e(
                    TAG,
                    "Failed writing save chunk",
                    e
                )


                runOnUiThread {


                    webView.evaluateJavascript(
                        "window.__patchySaveError = true;",
                        null
                    )
                }
            }
        }


        // ----------------------------------------------
        // FINISH SAVE
        // ----------------------------------------------

        @JavascriptInterface
        fun finishSave() {


            try {


                synchronized(
                    saveLock
                ) {


                    saveOutputStream
                        ?.flush()


                    saveOutputStream
                        ?.close()


                    saveOutputStream =
                        null


                    val uri =
                        saveUri


                    if (
                        uri != null
                    ) {


                        // ----------------------------------
                        // Publikasikan file.
                        //
                        // Sebelumnya IS_PENDING = 1
                        // Sekarang menjadi 0.
                        // ----------------------------------

                        val values =
                            ContentValues().apply {

                                put(
                                    MediaStore.Downloads
                                        .IS_PENDING,
                                    0
                                )
                            }


                        contentResolver.update(
                            uri,
                            values,
                            null,
                            null
                        )
                    }


                    saveUri =
                        null
                }


                Log.d(
                    TAG,
                    "Save finished"
                )


                runOnUiThread {


                    Toast.makeText(
                        this@MainActivity,
                        "File berhasil disimpan di Download/Patchy",
                        Toast.LENGTH_SHORT
                    ).show()


                    webView.evaluateJavascript(
                        "window.__patchySaveFinished = true;",
                        null
                    )
                }


            } catch (
                e: Exception
            ) {


                Log.e(
                    TAG,
                    "Failed finishing save",
                    e
                )


                runOnUiThread {


                    webView.evaluateJavascript(
                        "window.__patchySaveError = true;",
                        null
                    )
                }
            }
        }


        // ----------------------------------------------
        // CANCEL SAVE
        // ----------------------------------------------

        @JavascriptInterface
        fun cancelSave() {


            synchronized(
                saveLock
            ) {


                try {

                    saveOutputStream
                        ?.close()

                } catch (
                    _: Exception
                ) {
                }


                saveOutputStream =
                    null


                val uri =
                    saveUri


                if (
                    uri != null
                ) {


                    try {

                        contentResolver.delete(
                            uri,
                            null,
                            null
                        )

                    } catch (
                        _: Exception
                    ) {
                    }
                }


                saveUri =
                    null
            }


            Log.d(
                TAG,
                "Save cancelled"
            )
        }
    }


    // ==================================================
    // SANITIZE FILE NAME
    // ==================================================

    private fun sanitizeFileName(
        fileName: String
    ): String {


        var result =
            fileName


        // Hilangkan path yang tidak diinginkan
        result =
            result.replace(
                "/",
                "_"
            )


        result =
            result.replace(
                "\\",
                "_"
            )


        result =
            result.replace(
                ":",
                "_"
            )


        result =
            result.replace(
                "*",
                "_"
            )


        result =
            result.replace(
                "?",
                "_"
            )


        result =
            result.replace(
                "\"",
                "_"
            )


        result =
            result.replace(
                "<",
                "_"
            )


        result =
            result.replace(
                ">",
                "_"
            )


        result =
            result.replace(
                "|",
                "_"
            )


        result =
            result.trim()


        if (
            result.isBlank()
        ) {

            result =
                "Patchy-export"
        }


        return result
    }


    // ==================================================
    // INSTALL PATCHY SAVE BRIDGE
    // ==================================================

    private fun installPatchySaveBridge() {


        val script =
            """
            (function() {

                // ==========================================
                // CEGAH INSTALL BERULANG
                // ==========================================

                if (
                    window.__patchyAndroidSaveInstalled
                ) {
                    return;
                }


                // ==========================================
                // PASTIKAN BRIDGE ANDROID ADA
                // ==========================================

                if (
                    !window.PatchyAndroid
                ) {

                    console.error(
                        "PatchyAndroid bridge tidak tersedia"
                    );

                    return;
                }


                window.__patchyAndroidSaveInstalled =
                    true;


                window.__patchySaveReady =
                    false;


                window.__patchySaveCancelled =
                    false;


                window.__patchySaveFinished =
                    false;


                window.__patchySaveError =
                    false;


                // ==========================================
                // KIRIM BLOB KE ANDROID
                // ==========================================

                async function sendBlobToAndroid(
                    blob,
                    fileName
                ) {


                    try {


                        window.__patchySaveReady =
                            false;


                        window.__patchySaveCancelled =
                            false;


                        window.__patchySaveFinished =
                            false;


                        window.__patchySaveError =
                            false;


                        // ----------------------------------
                        // Minta Android membuat file
                        // ----------------------------------

                        PatchyAndroid.prepareSave(
                            fileName,
                            blob.type ||
                                "application/octet-stream"
                        );


                        // ----------------------------------
                        // Tunggu Android siap
                        // ----------------------------------

                        while (
                            !window.__patchySaveReady &&
                            !window.__patchySaveCancelled &&
                            !window.__patchySaveError
                        ) {


                            await new Promise(
                                resolve =>
                                    setTimeout(
                                        resolve,
                                        50
                                    )
                            );
                        }


                        // ----------------------------------
                        // Gagal / dibatalkan
                        // ----------------------------------

                        if (
                            window.__patchySaveCancelled ||
                            window.__patchySaveError
                        ) {

                            console.error(
                                "Patchy Android: save dibatalkan"
                            );

                            return;
                        }


                        // ==================================
                        // CHUNK SIZE
                        // ==================================

                        const chunkSize =
                            512 * 1024;


                        const totalChunks =
                            Math.ceil(
                                blob.size /
                                chunkSize
                            );


                        console.log(
                            "Patchy Android: " +
                            "mengirim " +
                            totalChunks +
                            " chunk"
                        );


                        // ==================================
                        // KIRIM SATU PER SATU
                        // ==================================

                        for (
                            let index = 0;
                            index < totalChunks;
                            index++
                        ) {


                            const start =
                                index *
                                chunkSize;


                            const end =
                                Math.min(
                                    start +
                                    chunkSize,
                                    blob.size
                                );


                            const buffer =
                                await blob
                                    .slice(
                                        start,
                                        end
                                    )
                                    .arrayBuffer();


                            const bytes =
                                new Uint8Array(
                                    buffer
                                );


                            // ----------------------------------
                            // Uint8Array → binary string
                            // ----------------------------------

                            let binary =
                                "";


                            const step =
                                0x8000;


                            for (
                                let i = 0;
                                i < bytes.length;
                                i += step
                            ) {


                                binary +=
                                    String.fromCharCode.apply(
                                        null,
                                        bytes.subarray(
                                            i,
                                            Math.min(
                                                i + step,
                                                bytes.length
                                            )
                                        )
                                    );
                            }


                            // ----------------------------------
                            // binary → Base64
                            // ----------------------------------

                            const base64 =
                                btoa(
                                    binary
                                );


                            // ----------------------------------
                            // Kirim ke Android
                            // ----------------------------------

                            PatchyAndroid.writeChunk(
                                base64
                            );


                            // ----------------------------------
                            // Beri WebView kesempatan
                            // bernapas
                            // ----------------------------------

                            await new Promise(
                                resolve =>
                                    setTimeout(
                                        resolve,
                                        0
                                    )
                            );


                            // ----------------------------------
                            // Periksa error
                            // ----------------------------------

                            if (
                                window.__patchySaveError ||
                                window.__patchySaveCancelled
                            ) {

                                console.error(
                                    "Patchy Android: " +
                                    "save berhenti"
                                );

                                return;
                            }
                        }


                        // ==================================
                        // SEMUA DATA SUDAH TERKIRIM
                        // ==================================

                        PatchyAndroid.finishSave();


                        console.log(
                            "Patchy Android: " +
                            "save selesai"
                        );


                    } catch (
                        error
                    ) {


                        console.error(
                            "Patchy Android save error:",
                            error
                        );


                        PatchyAndroid.cancelSave();
                    }
                }


                // ==========================================
                // HOOK HTMLAnchorElement.click()
                // ==========================================
                //
                // Patchy menggunakan:
                //
                // anchor.href = blob:...
                // anchor.download = "nama.png"
                // anchor.click()
                //
                // Jadi kita hook langsung prototype
                // click(), bukan document click event.
                // ==========================================

                const originalAnchorClick =
                    HTMLAnchorElement.prototype.click;


                HTMLAnchorElement.prototype.click =
                    function() {


                        try {


                            const link =
                                this;


                            const href =
                                link.href ||
                                "";


                            const fileName =
                                link.download ||
                                "";


                            // ----------------------------------
                            // Hanya intercept:
                            //
                            // <a download>
                            //
                            // dengan Blob URL.
                            // ----------------------------------

                            if (
                                fileName &&
                                href.startsWith(
                                    "blob:"
                                )
                            ) {


                                console.log(
                                    "Patchy Android: " +
                                    "download intercepted: " +
                                    fileName
                                );


                                // ----------------------------------
                                // Jangan jalankan click asli.
                                //
                                // Kalau dijalankan, WebView akan
                                // mencoba download menggunakan
                                // mekanisme browser.
                                // ----------------------------------

                                fetch(
                                    href
                                )
                                .then(
                                    response =>
                                        response.blob()
                                )
                                .then(
                                    blob =>
                                        sendBlobToAndroid(
                                            blob,
                                            fileName
                                        )
                                )
                                .catch(
                                    error => {


                                        console.error(
                                            "Could not read " +
                                            "Patchy Blob:",
                                            error
                                        );


                                        PatchyAndroid
                                            .cancelSave();
                                    }
                                );


                                return;
                            }


                        } catch (
                            error
                        ) {


                            console.error(
                                "Patchy Android click hook error:",
                                error
                            );
                        }


                        // ----------------------------------
                        // Bukan download Patchy.
                        //
                        // Biarkan perilaku asli.
                        // ----------------------------------

                        return originalAnchorClick.call(
                            this
                        );
                    };


                // ==========================================
                // DEBUG
                // ==========================================

                console.log(
                    "Patchy Android Save bridge installed"
                );


            })();
            """.trimIndent()


        webView.evaluateJavascript(
            script,
            null
        )
    }


    // ==================================================
    // CROSS ORIGIN ISOLATION
    // ==================================================

    private fun configureCrossOriginIsolation() {


        val isolationSupported =
            WebViewFeature.isFeatureSupported(
                WebViewFeature
                    .CROSS_ORIGIN_ISOLATED_ALLOWLIST
            )


        val multiProfileSupported =
            WebViewFeature.isFeatureSupported(
                WebViewFeature
                    .MULTI_PROFILE
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
                .getProfile(
                    webView
                )
                .setCrossOriginIsolatedAllowlist(
                    setOf(
                        PATCHY_ORIGIN
                    )
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


    // ==================================================
    // FULLSCREEN / SYSTEM BAR
    // ==================================================

    private fun hideSystemBars() {


        @Suppress("DEPRECATION")

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }


    // ==================================================
    // PASTIKAN SYSTEM BAR TETAP TERSEMBUNYI
    // ==================================================

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {


        super.onWindowFocusChanged(
            hasFocus
        )


        if (
            hasFocus
        ) {

            hideSystemBars()
        }
    }


    // ==================================================
    // HASIL FILE PICKER
    // ==================================================

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


        // ----------------------------------------------
        // Ini HANYA untuk OPEN.
        // Save tidak memakai ActivityResult.
        // ----------------------------------------------

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


        filePathCallback =
            null


        // ----------------------------------------------
        // User batal memilih file
        // ----------------------------------------------

        if (
            resultCode != RESULT_OK ||
            data == null
        ) {


            callback?.onReceiveValue(
                null
            )


            return
        }


        val uris =
            mutableListOf<Uri>()


        // ----------------------------------------------
        // Multiple file
        // ----------------------------------------------

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


        // ----------------------------------------------
        // Single file
        // ----------------------------------------------

        if (
            uris.isEmpty()
        ) {


            data.data?.let { uri ->

                uris.add(
                    uri
                )
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


    // ==================================================
    // DESTROY
    // ==================================================

    override fun onDestroy() {


        // ----------------------------------------------
        // Bersihkan callback file picker
        // ----------------------------------------------

        filePathCallback
            ?.onReceiveValue(
                null
            )


        filePathCallback =
            null


        // ----------------------------------------------
        // Bersihkan save stream
        // ----------------------------------------------

        synchronized(
            saveLock
        ) {


            try {

                saveOutputStream
                    ?.close()

            } catch (
                _: Exception
            ) {
            }


            saveOutputStream =
                null


            saveUri =
                null
        }


        // ----------------------------------------------
        // Destroy WebView
        // ----------------------------------------------

        if (
            ::webView.isInitialized
        ) {


            webView.stopLoading()

            webView.destroy()
        }


        super.onDestroy()
    }
}