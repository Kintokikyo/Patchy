package com.kintokikyo.patchy

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
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
import android.provider.DocumentsContract
import androidx.webkit.ScriptHandler
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

        private const val SAVE_FOLDER_REQUEST_CODE =
            2001
    }


    private lateinit var webView: WebView

    private lateinit var assetLoader:
        WebViewAssetLoader


    // ==============================================
    // ANDROID FILE PICKER - OPEN
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

    private var pendingSaveFileName:
        String = ""

    private var pendingSaveMimeType:
        String = "application/octet-stream"

    @Volatile
    private var saveReady =
        false

    @Volatile
    private var saveCancelled =
        false

    @Volatile
    private var saveError =
        false

    private val saveLock =
        Any()


    // ==============================================
    // DOCUMENT START JAVASCRIPT
    // ==============================================

    private var patchySaveScriptHandler:
        ScriptHandler? = null


    // ==================================================
    // ON CREATE
    // ==================================================

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
                            response.responseHeaders
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
                // ANDROID FILE PICKER
                // OPEN
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
        // INSTALL SAVE JAVASCRIPT
        //
        // HARUS SEBELUM loadUrl()
        //
        // API ini bisa masuk ke iframe.
        // ==========================================

        installPatchySaveScript()


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

                synchronized(saveLock) {

                    // Bersihkan save sebelumnya
                    try {
                        saveOutputStream?.close()
                    } catch (_: Exception) {
                    }


                    saveOutputStream =
                        null

                    saveUri =
                        null


                    pendingSaveFileName =
                        sanitizeFileName(
                            fileName
                        )


                    // ----------------------------------
                    // Tentukan MIME type berdasarkan
                    // ekstensi nama file.
                    //
                    // Jangan mengandalkan blob.type karena
                    // pada beberapa Android document provider
                    // hal tersebut dapat membuat ekstensi
                    // tambahan seperti .txt.
                    // ----------------------------------

                    val lowerFileName =
                        pendingSaveFileName
                            .lowercase()

                    pendingSaveMimeType =
                        when {

                            lowerFileName.endsWith(
                                ".png"
                            ) ->
                                "image/png"

                            lowerFileName.endsWith(
                                ".jpg"
                            ) ||
                            lowerFileName.endsWith(
                                ".jpeg"
                            ) ->
                                "image/jpeg"

                            lowerFileName.endsWith(
                                ".webp"
                            ) ->
                                "image/webp"

                            lowerFileName.endsWith(
                                ".gif"
                            ) ->
                                "image/gif"

                            lowerFileName.endsWith(
                                ".bmp"
                            ) ->
                                "image/bmp"

                            lowerFileName.endsWith(
                                ".tif"
                            ) ||
                            lowerFileName.endsWith(
                                ".tiff"
                            ) ->
                                "image/tiff"

                            lowerFileName.endsWith(
                                ".svg"
                            ) ->
                                "image/svg+xml"

                            lowerFileName.endsWith(
                                ".psd"
                            ) ->
                                "application/vnd.adobe.photoshop"

                            lowerFileName.endsWith(
                                ".psb"
                            ) ->
                                "application/octet-stream"

                            else ->
                                "application/octet-stream"
                        }


                    saveReady =
                        false

                    saveCancelled =
                        false

                    saveError =
                        false
                }


                Log.d(
                    TAG,
                    "Meminta folder untuk: " +
                        pendingSaveFileName
                )


                runOnUiThread {

                    val intent =
                        Intent(
                            Intent.ACTION_OPEN_DOCUMENT_TREE
                        ).apply {

                            addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            )
                        }


                    startActivityForResult(
                        intent,
                        SAVE_FOLDER_REQUEST_CODE
                    )
                }


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed preparing save folder",
                    e
                )

                saveError =
                    true
            }
        }


        // ----------------------------------------------
        // IS SAVE READY?
        // ----------------------------------------------

        @JavascriptInterface
        fun isSaveReady():
            Boolean {

            return saveReady
        }


        // ----------------------------------------------
        // IS SAVE CANCELLED?
        // ----------------------------------------------

        @JavascriptInterface
        fun isSaveCancelled():
            Boolean {

            return saveCancelled
        }


        // ----------------------------------------------
        // IS SAVE ERROR?
        // ----------------------------------------------

        @JavascriptInterface
        fun isSaveError():
            Boolean {

            return saveError
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


                synchronized(saveLock) {

                    val output =
                        saveOutputStream
                            ?: throw IllegalStateException(
                                "OutputStream tidak tersedia"
                            )


                    output.write(
                        bytes
                    )
                }


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed writing save chunk",
                    e
                )

                saveError =
                    true
            }
        }


        // ----------------------------------------------
        // FINISH SAVE
        // ----------------------------------------------

        @JavascriptInterface
        fun finishSave() {

            try {

                synchronized(saveLock) {

                    saveOutputStream
                        ?.flush()

                    saveOutputStream
                        ?.close()

                    saveOutputStream =
                        null


                    val uri =
                        saveUri


                    saveUri =
                        null


                    saveReady =
                        false


                    if (uri == null) {

                        throw IllegalStateException(
                            "URI save tidak tersedia"
                        )
                    }


                    Log.d(
                        TAG,
                        "Save finished: $uri"
                    )
                }


                runOnUiThread {

                    Toast.makeText(
                        this@MainActivity,
                        "File berhasil disimpan",
                        Toast.LENGTH_SHORT
                    ).show()
                }


            } catch (e: Exception) {

                Log.e(
                    TAG,
                    "Failed finishing save",
                    e
                )

                saveError =
                    true
            }
        }


        // ----------------------------------------------
        // CANCEL SAVE
        // ----------------------------------------------

        @JavascriptInterface
        fun cancelSave() {

            synchronized(saveLock) {

                try {
                    saveOutputStream
                        ?.close()
                } catch (_: Exception) {
                }


                saveOutputStream =
                    null


                val uri =
                    saveUri


                saveUri =
                    null


                if (uri != null) {

                    try {

                        contentResolver.delete(
                            uri,
                            null,
                            null
                        )

                    } catch (_: Exception) {
                    }
                }


                saveReady =
                    false

                saveCancelled =
                    true
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
    // INSTALL PATCHY SAVE JAVASCRIPT
    // ==================================================

    private fun installPatchySaveScript() {

        if (
            !WebViewFeature.isFeatureSupported(
                WebViewFeature.DOCUMENT_START_SCRIPT
            )
        ) {

            Log.e(
                TAG,
                "DOCUMENT_START_SCRIPT tidak didukung"
            )

            return
        }


        try {

            patchySaveScriptHandler =
                WebViewCompat
                    .addDocumentStartJavaScript(
                        webView,
                        getPatchySaveJavaScript(),
                        setOf(
                            PATCHY_ORIGIN
                        )
                    )


            Log.d(
                TAG,
                "Patchy Save JS berhasil dipasang"
            )

        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal memasang Patchy Save JS",
                e
            )
        }
    }


    // ==================================================
    // PATCHY SAVE JAVASCRIPT
    // ==================================================

    private fun getPatchySaveJavaScript():
        String {

        return """

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

                
                window.__patchyAndroidSaveInstalled =
                    true;


                // ==========================================
                // KIRIM BLOB KE ANDROID
                // ==========================================

                async function sendBlobToAndroid(
                    blob,
                    fileName
                ) {
                
                    // CEK BRIDGE ANDROID
                    if (!window.PatchyAndroid) {
                        console.error("PatchyAndroid bridge tidak tersedia");
                        return;
                    }

                    try {

                        console.log(
                            "Patchy Android: " +
                            "download intercepted: " +
                            fileName
                        );


                        // ----------------------------------
                        // Reset status
                        // ----------------------------------

                        window.__patchySaveError =
                            false;


                        // ----------------------------------
                        // Minta Android memilih folder
                        // ----------------------------------

                        PatchyAndroid.prepareSave(
                            fileName,
                            blob.type ||
                                "application/octet-stream"
                        );


                        // ----------------------------------
                        // Tunggu user memilih folder
                        // ----------------------------------

                        while (
                            !PatchyAndroid.isSaveReady() &&
                            !PatchyAndroid.isSaveCancelled() &&
                            !PatchyAndroid.isSaveError()
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
                        // User batal / error
                        // ----------------------------------

                        if (
                            PatchyAndroid.isSaveCancelled() ||
                            PatchyAndroid.isSaveError()
                        ) {

                            console.error(
                                "Patchy Android: " +
                                "save dibatalkan"
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
                            // Binary → Base64
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
                            // Beri WebView kesempatan bernapas
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
                                PatchyAndroid.isSaveError() ||
                                PatchyAndroid.isSaveCancelled()
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
                            // Intercept:
                            //
                            // <a download>
                            // +
                            // blob:
                            // ----------------------------------

                            if (
                                fileName &&
                                href.startsWith(
                                    "blob:"
                                )
                            ) {

                                console.log(
                                    "Patchy Android: " +
                                    "Blob download ditemukan: " +
                                    fileName
                                );


                                // ----------------------------------
                                // Jangan jalankan click asli
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
                        // Biarkan perilaku asli.
                        // ----------------------------------

                        return originalAnchorClick.call(
                            this
                        );
                    };


                console.log(
                    "Patchy Android Save bridge installed"
                );


            })();

        """.trimIndent()
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
            "CROSS_ORIGIN_ISOLATED_ALLOWLIST " +
                "supported = " +
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


    // ==============================================
    // OPEN FILE
    // ==============================================

    if (
        requestCode ==
        FILE_CHOOSER_REQUEST_CODE
    ) {

        Log.d(
            TAG,
            "File picker result: $resultCode"
        )


        val callback =
            filePathCallback


        filePathCallback =
            null


        // User batal memilih file

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
                i in 0 until
                    clipData.itemCount
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


        return
    }


    // ==============================================
    // SAVE FOLDER
    // ==============================================

    if (
        requestCode ==
        SAVE_FOLDER_REQUEST_CODE
    ) {

        // User batal memilih folder

        if (
            resultCode != RESULT_OK ||
            data?.data == null
        ) {

            Log.d(
                TAG,
                "Pemilihan folder dibatalkan"
            )


            saveCancelled =
                true

            return
        }


        val treeUri =
            data.data!!


        try {

            // ------------------------------------------
            // Simpan permission folder
            // ------------------------------------------

            val takeFlags =
                data.flags and (
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )


            try {

                contentResolver
                    .takePersistableUriPermission(
                        treeUri,
                        takeFlags
                    )

            } catch (_: Exception) {
            }


            Log.d(
                TAG,
                "Folder dipilih: $treeUri"
            )


            // ------------------------------------------
            // UBAH TREE URI MENJADI DOCUMENT URI
            // ------------------------------------------
            //
            // ACTION_OPEN_DOCUMENT_TREE memberikan
            // tree URI.
            //
            // createDocument() membutuhkan URI dokumen
            // yang menunjuk ke folder parent.
            // ------------------------------------------

            val parentDocumentUri =
                DocumentsContract.buildDocumentUriUsingTree(
                    treeUri,
                    DocumentsContract.getTreeDocumentId(
                        treeUri
                    )
                )


            Log.d(
                TAG,
                "Parent document URI: $parentDocumentUri"
            )


            // ------------------------------------------
            // Buat file di folder tersebut
            // ------------------------------------------

            val fileUri =
                DocumentsContract.createDocument(
                    contentResolver,
                    parentDocumentUri,
                    pendingSaveMimeType,
                    pendingSaveFileName
                )


            if (
                fileUri == null
            ) {

                throw IllegalStateException(
                    "Tidak bisa membuat file " +
                        "di folder yang dipilih"
                )
            }


            Log.d(
                TAG,
                "File berhasil dibuat: $fileUri"
            )


            // ------------------------------------------
            // Buka OutputStream
            // ------------------------------------------

            val output =
                contentResolver
                    .openOutputStream(
                        fileUri
                    )


            if (
                output == null
            ) {

                try {

                    contentResolver.delete(
                        fileUri,
                        null,
                        null
                    )

                } catch (_: Exception) {
                }


                throw IllegalStateException(
                    "Tidak bisa membuka OutputStream"
                )
            }


            // ------------------------------------------
            // Simpan state
            // ------------------------------------------

            synchronized(saveLock) {

                saveUri =
                    fileUri

                saveOutputStream =
                    output

                saveReady =
                    true

                saveCancelled =
                    false

                saveError =
                    false
            }


            Log.d(
                TAG,
                "Save siap: $fileUri"
            )


        } catch (e: Exception) {

            Log.e(
                TAG,
                "Gagal membuat file save",
                e
            )


            saveReady =
                false

            saveError =
                true
        }


        return
    }
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

        synchronized(saveLock) {

            try {

                saveOutputStream
                    ?.close()

            } catch (_: Exception) {
            }


            saveOutputStream =
                null


            saveUri =
                null


            saveReady =
                false
        }


        // ----------------------------------------------
        // Hapus injected script
        // ----------------------------------------------

        try {

            patchySaveScriptHandler
                ?.remove()

        } catch (_: Exception) {
        }


        patchySaveScriptHandler =
            null


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