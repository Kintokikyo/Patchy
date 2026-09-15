package com.kintokikyo.patchy

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import kotlin.concurrent.thread

class MainActivity : Activity() {

    companion object {
        private const val TAG = "PatchyServer"
        private const val PORT = 8973
    }

    private lateinit var webView: WebView
    private var serverSocket: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            builtInZoomControls = false
            displayZoomControls = false
        }

        webView.webViewClient = object : WebViewClient() {

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
                request: android.webkit.WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
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
                        "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                )
                return true
            }
        }

        setContentView(webView)

        startLocalServer()
    }

    private fun startLocalServer() {

        thread(name = "PatchyServer") {

            try {

                serverSocket = ServerSocket(PORT)

                Log.d(TAG, "Server started on 127.0.0.1:$PORT")

                runOnUiThread {
                    webView.loadUrl(
                        "http://127.0.0.1:$PORT/patchy.html"
                    )
                }

                while (!serverSocket!!.isClosed) {

                    val socket = serverSocket!!.accept()

                    thread(name = "PatchyRequest") {
                        handleRequest(socket)
                    }
                }

            } catch (e: Exception) {

                Log.e(TAG, "Server stopped", e)
            }
        }
    }

    private fun handleRequest(socket: Socket) {

        socket.use {

            try {

                val reader = BufferedReader(
                    InputStreamReader(socket.getInputStream())
                )

                val requestLine = reader.readLine() ?: return

                val parts = requestLine.split(" ")

                if (parts.size < 2) {
                    return
                }

                val method = parts[0]
                val path = parts[1]

                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                }

                if (method != "GET" && method != "HEAD") {
                    sendStatus(socket, 405, "Method Not Allowed")
                    return
                }

                val cleanPath = path.substringBefore("?")

                val assetPath =
                    if (cleanPath == "/" || cleanPath.isEmpty()) {
                        "patchy.html"
                    } else {
                        cleanPath.removePrefix("/")
                    }

                // Prevent ../ path traversal.
                if (
                    assetPath.contains("..") ||
                    assetPath.startsWith("/")
                ) {
                    sendStatus(socket, 403, "Forbidden")
                    return
                }

                Log.d(TAG, "Request: $assetPath")

                val input = try {
                    assets.open("patchy/$assetPath")
                } catch (e: Exception) {
                    Log.e(TAG, "Asset not found: $assetPath")
                    sendStatus(socket, 404, "Not Found")
                    return
                }

                val availableLength = input.available().toLong()

                val mimeType =
                    URLConnection.guessContentTypeFromName(assetPath)
                        ?: when {
                            assetPath.endsWith(".wasm") ->
                                "application/wasm"

                            assetPath.endsWith(".data") ->
                                "application/octet-stream"

                            assetPath.endsWith(".js") ->
                                "text/javascript; charset=utf-8"

                            assetPath.endsWith(".html") ->
                                "text/html; charset=utf-8"

                            assetPath.endsWith(".svg") ->
                                "image/svg+xml"

                            assetPath.endsWith(".png") ->
                                "image/png"

                            assetPath.endsWith(".ico") ->
                                "image/x-icon"

                            else ->
                                "application/octet-stream"
                        }

                val output = socket.getOutputStream()

                val headers =
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $mimeType\r\n" +
                    "Content-Length: $availableLength\r\n" +
                    "Cross-Origin-Opener-Policy: same-origin\r\n" +
                    "Cross-Origin-Embedder-Policy: require-corp\r\n" +
                    "Cross-Origin-Resource-Policy: same-origin\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"

                output.write(headers.toByteArray())

                if (method == "GET") {

                    // Stream the asset instead of input.readBytes().
                    // This prevents 26-70 MB assets from being copied
                    // completely into a Kotlin byte array.
                    val buffer = ByteArray(64 * 1024)

                    while (true) {

                        val count = input.read(buffer)

                        if (count <= 0) {
                            break
                        }

                        output.write(buffer, 0, count)
                    }
                }

                output.flush()

                input.close()

                Log.d(TAG, "Served: $assetPath")

            } catch (e: Exception) {

                Log.e(TAG, "Request failed", e)
            }
        }
    }

    private fun sendStatus(
        socket: Socket,
        code: Int,
        message: String
    ) {

        try {

            val output = socket.getOutputStream()

            val body = "$code $message"

            val response =
                "HTTP/1.1 $code $message\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${body.toByteArray().size}\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                body

            output.write(response.toByteArray())
            output.flush()

        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {

        serverSocket?.close()

        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }

        super.onDestroy()
    }
}
