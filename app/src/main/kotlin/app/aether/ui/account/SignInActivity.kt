package app.aether.ui.account

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.aether.AetherApp
import app.aether.core.designsystem.component.GlassPill
import app.aether.core.designsystem.component.GridDots
import app.aether.core.designsystem.component.Txt
import app.aether.core.designsystem.theme.Ink
import app.aether.core.designsystem.theme.Type
import app.aether.ytmusic.WebSession

/**
 * Signing in to YouTube Music on Google's own page (after PODIUM's). AETHER never sees or keeps the
 * password: the page is Google's, and two-step checks work as in a browser. What AETHER keeps is the
 * session the page leaves behind, sealed with the phone's Keystore ([app.aether.core.data.Account]).
 *
 * The web view is shut tight: https only, only Google's and YouTube's own hosts (regional Google
 * domains included), no file or content access, no bridge into the app, no pop-ups, Safe Browsing
 * on, no cache, and the screen kept out of screenshots. Web state is wiped before and after.
 */
class SignInActivity : ComponentActivity() {
    private var webView: WebView? = null
    private var checking by mutableStateOf(false)
    private var stopped by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        var ready by mutableStateOf(false)
        if (savedInstanceState == null) wipe { ready = true } else ready = true
        setContent {
            Column(Modifier.fillMaxSize().background(Ink.Black).safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt("Sign in to YouTube Music", Type.Title, modifier = Modifier.weight(1f))
                    GlassPill("Cancel", onClick = { finish() }, small = true)
                }
                Txt(
                    "You sign in on Google's own page. AETHER never sees your password; it keeps only the session, sealed on this phone.",
                    Type.Meta,
                    color = Ink.Grey,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 10.dp),
                )
                stopped?.let { Txt(it, Type.Meta, color = Ink.Grey, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) }
                Box(Modifier.fillMaxSize()) {
                    if (ready) AndroidView(factory = ::page, onRelease = { it.destroy() }, modifier = Modifier.fillMaxSize())
                    if (checking) Column(Modifier.fillMaxSize().background(Ink.Black), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        GridDots()
                        Spacer(Modifier.width(1.dp).padding(top = 14.dp))
                        Txt("Signing you in", Type.Body, color = Ink.Grey)
                    }
                }
            }
        }
    }

    // Google's sign-in page needs JavaScript; nothing of AETHER's is exposed to it.
    @SuppressLint("SetJavaScriptEnabled")
    private fun page(context: Context): WebView = WebView(context).apply {
        val view = this
        webView = view
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            setGeolocationEnabled(false)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_NO_CACHE
            safeBrowsingEnabled = true
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(view, true)
        }
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (allowed(request.url)) return false
                if (request.isForMainFrame) stopped = "That page isn't part of signing in, so it was stopped."
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) = look(url)
            override fun onPageFinished(view: WebView, url: String) = look(url)

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                stopped = "The page's certificate didn't check out, so it was stopped."
            }
        }
        loadUrl(START)
    }

    /** Back on YouTube Music with a session: keep it, sealed, and close. */
    private fun look(url: String) {
        if (checking) return
        val host = Uri.parse(url).host ?: return
        if (host != "music.youtube.com") return
        val header = CookieManager.getInstance().getCookie(ORIGIN) ?: return
        if (WebSession.parse(header) == null) return
        checking = true
        val container = (application as AetherApp).container
        container.account.signIn(header)
        container.catalog.clear()
        wipe { finish() }
    }

    private fun wipe(then: () -> Unit) {
        CookieManager.getInstance().apply {
            removeAllCookies { then() }
            flush()
        }
        WebStorage.getInstance().deleteAllData()
        webView?.apply {
            clearCache(true)
            clearHistory()
            clearFormData()
        }
    }

    override fun onDestroy() {
        if (isFinishing && !checking) wipe {}
        super.onDestroy()
    }

    companion object {
        private const val ORIGIN = "https://music.youtube.com"
        private const val START = "https://accounts.google.com/ServiceLogin?service=youtube&passive=true&continue=https%3A%2F%2Fmusic.youtube.com%2F"
        private val HOSTS = listOf("google.com", "youtube.com", "gstatic.com", "googleusercontent.com", "ggpht.com", "ytimg.com", "googleapis.com")
        private val REGIONAL_GOOGLE = Regex("""^([a-z0-9-]+\.)*google\.(co\.[a-z]{2}|com\.[a-z]{2}|[a-z]{2})$""")

        fun intent(context: Context) = Intent(context, SignInActivity::class.java)

        /** https only, and only Google's and YouTube's own hosts, regional Google domains included. */
        private fun allowed(uri: Uri): Boolean {
            if (uri.scheme != "https") return false
            val host = uri.host?.lowercase() ?: return false
            return HOSTS.any { host == it || host.endsWith(".$it") } || REGIONAL_GOOGLE.matches(host)
        }
    }
}
