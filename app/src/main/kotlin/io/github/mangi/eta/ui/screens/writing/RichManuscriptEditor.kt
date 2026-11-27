package io.github.mangi.eta.ui.screens.writing

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebMessage
import android.webkit.WebMessagePort
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.util.Locale
import org.json.JSONObject
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class RichManuscriptSession {
    internal var view: MuyaWebView? = null

    fun flush(then: () -> Unit) {
        view?.flush(then) ?: then()
    }
}

@Composable
internal fun RichManuscriptEditor(
    text: String,
    session: RichManuscriptSession,
    onEdit: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val edit by rememberUpdatedState(onEdit)
    val unavailable by rememberUpdatedState(onUnavailable)
    var ready by remember { mutableStateOf(false) }
    val colors = MiuixTheme.colorScheme
    val palette = MuyaPalette(
        colors.surface.toArgb(), colors.onSurface.toArgb(), colors.primary.toArgb(),
        dark = colors.surface.luminance() < 0.45f,
    )

    Box(modifier) {
        AndroidView<View>(
            modifier = Modifier.fillMaxSize(),
            onReset = null,
            factory = { context ->
                try {
                    MuyaWebView(context).also { web ->
                        session.view = web
                        web.changed = { edit(it) }
                        web.ready = { ready = true }
                        web.unavailable = { unavailable() }
                        web.updateDocument(text, palette)
                        web.loadUrl(MUYA_DOCUMENT)
                    }
                } catch (_: RuntimeException) {
                    View(context).also { it.post { unavailable() } }
                }
            },
            update = { if (it is MuyaWebView) it.updateDocument(text, palette) },
            onRelease = {
                if (session.view === it) session.view = null
                if (it is MuyaWebView) it.release()
            },
        )
        if (!ready) WritingLoading()
    }
}

internal data class MuyaPalette(val background: Int, val foreground: Int, val accent: Int, val dark: Boolean) {
    private fun css(color: Int) = String.format(Locale.ROOT, "#%06X", color and 0x00ffffff)

    fun json(): JSONObject = JSONObject()
        .put("background", css(background))
        .put("foreground", css(foreground))
        .put("accent", css(accent))
        .put("dark", dark)
}

private val MUYA_ORIGIN = Uri.parse("https://qingjian-local.invalid")
private val MUYA_DOCUMENT = "${MUYA_ORIGIN}/muya/index.html"
private val LOCAL_ASSET = Regex("""/muya/[\w./-]+""")
private const val MAX_MANUSCRIPT_CHARS = 4 * 1024 * 1024

internal class MuyaWebView(context: Context) : WebView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private var port: WebMessagePort? = null
    private var palette = MuyaPalette(Color.WHITE, Color.BLACK, Color.BLUE, false)
    private var document = ""
    private var revision = 0
    private var connected = false
    private var released = false
    private var pendingFlush: (() -> Unit)? = null
    private val loadTimeout = Runnable { unavailable?.invoke() }
    private val flushTimeout = Runnable { completeFlush() }
    var changed: ((String) -> Unit)? = null
    var ready: (() -> Unit)? = null
    var unavailable: (() -> Unit)? = null

    init {
        isSaveEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.setSupportZoom(false)
        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                intercept(request)

            override fun onPageFinished(view: WebView, url: String) {
                if (url != MUYA_DOCUMENT || released || port != null) return
                val channel = createWebMessageChannel()
                port = channel[0]
                channel[0].setWebMessageCallback(object : WebMessagePort.WebMessageCallback() {
                    override fun onMessage(port: WebMessagePort, message: WebMessage) {
                        message.data?.let(::receive)
                    }
                }, handler)
                postWebMessage(WebMessage("qingjian-muya", arrayOf(channel[1])), MUYA_ORIGIN)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) unavailable?.invoke()
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                unavailable?.invoke()
                return true
            }
        }
        handler.postDelayed(loadTimeout, 8_000)
    }

    fun updateDocument(text: String, theme: MuyaPalette) {
        if (text.length > MAX_MANUSCRIPT_CHARS) {
            handler.post { if (!released) unavailable?.invoke() }
            return
        }
        val differentTheme = palette != theme
        palette = theme
        setBackgroundColor(theme.background)
        if (document != text) {
            document = text
            revision++
            if (connected) sendDocument()
        } else if (differentTheme && connected) {
            send(JSONObject().put("type", "theme").put("theme", palette.json()))
        }
    }

    fun flush(then: () -> Unit) {
        if (released || !connected || port == null) {
            then()
            return
        }
        pendingFlush = then
        send(JSONObject().put("type", "flush"))
        handler.removeCallbacks(flushTimeout)
        handler.postDelayed(flushTimeout, 400)
    }

    private fun receive(raw: String) {
        if (released || raw.length > MAX_MANUSCRIPT_CHARS * 6 + 256) return
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        if (message.optString("type") == "connected") {
            connected = true
            sendDocument()
            return
        }
        if (message.optInt("revision", -1) != revision) return
        when (message.optString("type")) {
            "ready" -> {
                handler.removeCallbacks(loadTimeout)
                ready?.invoke()
            }
            "edit" -> {
                val text = message.opt("text") as? String ?: return
                if (text.length > MAX_MANUSCRIPT_CHARS || text == document) return
                document = text
                changed?.invoke(text)
            }
            "flushed" -> completeFlush()
            "error" -> unavailable?.invoke()
        }
    }

    private fun sendDocument() = send(JSONObject()
        .put("type", "open")
        .put("revision", revision)
        .put("text", document)
        .put("theme", palette.json()))

    private fun send(message: JSONObject) {
        if (released) return
        try {
            port?.postMessage(WebMessage(message.toString()))
        } catch (_: IllegalStateException) {
            unavailable?.invoke()
        }
    }

    private fun completeFlush() {
        handler.removeCallbacks(flushTimeout)
        val callback = pendingFlush
        pendingFlush = null
        callback?.invoke()
    }

    fun release() {
        released = true
        handler.removeCallbacksAndMessages(null)
        pendingFlush = null
        port?.close()
        port = null
        stopLoading()
        destroy()
    }

    private fun intercept(request: WebResourceRequest): WebResourceResponse {
        val address = request.url
        val path = address.encodedPath.orEmpty()
        if (request.method != "GET" || address.scheme != MUYA_ORIGIN.scheme ||
            address.encodedAuthority != MUYA_ORIGIN.encodedAuthority || address.query != null ||
            !LOCAL_ASSET.matches(path) || path.split('/').any { it == ".." || it == "." }
        ) return rejectedResource()

        val mime = when (path.substringAfterLast('.')) {
            "html" -> "text/html"
            "js", "mjs" -> "text/javascript"
            "css" -> "text/css"
            "png" -> "image/png"
            "svg" -> "image/svg+xml"
            "jpg" -> "image/jpeg"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "ttf" -> "font/ttf"
            else -> return rejectedResource()
        }
        return try {
            WebResourceResponse(mime, "UTF-8", context.assets.open(path.drop(1)))
        } catch (_: FileNotFoundException) {
            rejectedResource()
        }
    }

    private fun rejectedResource() = WebResourceResponse(
        "text/plain", "UTF-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(byteArrayOf()),
    )
}
