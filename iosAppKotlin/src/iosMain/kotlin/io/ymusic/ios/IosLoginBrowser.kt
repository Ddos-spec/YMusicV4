package io.ymusic.ios

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.interop.UIKitView
import io.ymusic.ui.LoginWebViewState
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import platform.CoreGraphics.CGRectMake
import platform.Foundation.HTTPMethod
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject
/**
 * The iOS sign-in WebView (the shell's [AppGraph.loginBrowser]). Cookies land in the default
 * [WKWebsiteDataStore], which [IosWebViewCookieJar] wraps for engine HTTP. A non-null [userAgent]
 * (the captcha browser) replaces the default so the cleared challenge matches the engine's scrape.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalForeignApi::class)
@Composable
internal fun IosLoginBrowser(
    url: String,
    userAgent: String?,
    state: LoginWebViewState,
    onPageChanged: () -> Unit,
    modifier: Modifier,
) {
    val holder = remember {
        val config = WKWebViewConfiguration().apply {
            websiteDataStore = WKWebsiteDataStore.defaultDataStore()
        }
        val webView = WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = config)
        if (userAgent != null) webView.customUserAgent = userAgent
        val delegate = LoginNavDelegate(state, onPageChanged)
        webView.navigationDelegate = delegate // strong ref kept in the holder below
        state.back = { if (webView.canGoBack) webView.goBack() }
        NSURL.URLWithString(url)?.let { webView.loadRequest(NSURLRequest(it)) }
        LoginWebViewHolder(webView, delegate)
    }
    UIKitView(factory = { holder.webView }, modifier = modifier, interactive = true)
    DisposableEffect(Unit) {
        onDispose {
            state.back = null
            holder.webView.navigationDelegate = null
        }
    }
}

/** Retains the WKWebView and its (weakly-referenced) navigation delegate for the composition's life. */
private class LoginWebViewHolder(val webView: WKWebView, val delegate: NSObject)

@OptIn(ExperimentalForeignApi::class)
private class LoginNavDelegate(
    private val state: LoginWebViewState,
    private val onPageChanged: () -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {
    private var reissuedUrl: String? = null

    // The delegate methods share one ObjC selector family ("webView:…:") — Kotlin sees them as
    // conflicting overloads; the annotation allows the inherited-ObjC collision.

    // The sign-in's final hop (m.youtube.com/signin) is a Universal Link: allowed as a user-gesture
    // navigation, iOS hands it to the installed YouTube app and the WebView never gets the youtube
    // cookies. A programmatic loadRequest never opens app links, so re-issue it from code. (The
    // private "allow without app link" policy value can't cross K/N's strict enum.)
    @ObjCSignatureOverride
    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val request = decidePolicyForNavigationAction.request
        val url = request.URL
        val host = url?.host?.lowercase()
        val isYouTube = host != null && (host == "youtube.com" || host.endsWith(".youtube.com"))
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        val urlString = url?.absoluteString
        if (!isYouTube || !isMainFrame || request.HTTPMethod != "GET" || urlString == reissuedUrl) {
            if (urlString == reissuedUrl) reissuedUrl = null
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        reissuedUrl = urlString
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        webView.loadRequest(request)
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didStartProvisionalNavigation: WKNavigation?) {
        state.currentUrl = webView.URL?.absoluteString
        state.canGoBack = webView.canGoBack
        state.progress = 0.15f
        onPageChanged()
    }

    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        state.currentUrl = webView.URL?.absoluteString
        state.title = webView.title
        state.canGoBack = webView.canGoBack
        state.progress = 1f
        onPageChanged()
    }
}
