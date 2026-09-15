package com.kintokikyo.patchy

import android.app.Activity
import android.os.Bundle
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLConnection
import kotlin.concurrent.thread

class MainActivity : Activity() {

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

        webView.webViewClient = WebViewClient()

        setContentView(webView)

        startLocalServer()
    }

    private fun startLocalServer() {
        thread {
            try {
                serverSocket = ServerSocket(8973)

                runOnUiThread {
                    webView.loadUrl("http://127.0.0.1:8973/patchy.html")
                }

                while (!serverSocket!!.isClosed) {
                    val socket = serverSocket!!.accept()

                    thread {
                        handleRequest(socket)
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleRequest(socket: Socket) {

        socket.use {

            val reader = BufferedReader(
                InputStreamReader(socket.getInputStream())
            )

            val requestLine = reader.readLine() ?: return

            val path = requestLine.split(" ")[1]

            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }

            val cleanPath = path.substringBefore("?")

            val assetPath =
                if (cleanPath == "/") {
                    "patchy.html"
                } else {
                    cleanPath.removePrefix("/")
                }

            try {

                val input = assets.open("patchy/$assetPath")
                val data = input.readBytes()
                input.close()

                val mimeType =
                    URLConnection.guessContentTypeFromName(assetPath)
                        ?: when {
                            assetPath.endsWith(".wasm") ->
                                "application/wasm"

                            assetPath.endsWith(".data") ->
                                "application/octet-stream"

                            assetPath.endsWith(".js") ->
                                "application/javascript"

                            assetPath.endsWith(".html") ->
                                "text/html"

                            else ->
                                "application/octet-stream"
                        }

                val output = socket.getOutputStream()

                val headers = """
                    HTTP/1.1 200 OK
                    Content-Type: $mimeType
                    Content-Length: ${data.size}
                    Cross-Origin-Opener-Policy: same-origin
                    Cross-Origin-Embedder-Policy: require-corp
                    Cache-Control: no-cache
                    
                """.trimIndent().replace("\n", "\r\n")

                output.write(headers.toByteArray())
                output.write(data)
                output.flush()

            } catch (e: Exception) {

                val output = socket.getOutputStream()

                val response =
                    "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Length: 0\r\n" +
                    "\r\n"

                output.write(response.toByteArray())
                output.flush()
            }
        }
    }

    override fun onDestroy() {

        serverSocket?.close()

        webView.destroy()

        super.onDestroy()
    }
}
