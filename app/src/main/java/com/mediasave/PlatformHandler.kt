package com.mediasave

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

// Shared UA used by WebView and most platform HTTP calls
const val WEB_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/120.0.6099.144 Mobile Safari/537.36"

/**
 * Callbacks provided by MainActivity to platform handlers.
 * Handlers must not hold a reference to Activity or WebView directly.
 */
interface HandlerContext {
    /** Add a discovered media item. Thread-safe — may be called from any thread. */
    fun reportMedia(type: String, url: String)
    /** Update the status text shown to the user. Must be called on the UI thread or via runOnUi. */
    fun setStatus(msg: String)
    /** Read WebView cookies for the given domain URL (e.g. "https://www.instagram.com"). */
    fun getCookie(domain: String): String?
    /** Evaluate JavaScript in the WebView. Must be called on the UI thread. */
    fun injectJs(js: String)
    /** Post a Runnable to run on the UI thread after [ms] milliseconds. */
    fun postDelayed(ms: Long, action: () -> Unit)
    /** Run [action] on a new background thread. */
    fun runBackground(action: () -> Unit)
    /** Run [action] on the UI thread. */
    fun runOnUi(action: () -> Unit)
    /** Load a new URL in the WebView (must be called on the UI thread or via runOnUi). */
    fun navigateTo(url: String)
    /**
     * Load [url] in the WebView for display only — does NOT reset the download session or
     * clear capturedMedia. Use this when you've already extracted media and just want the
     * WebView to show a different page (e.g. navigate from the mobile share page to the
     * desktop player for the same video).
     */
    fun navigateForDisplay(url: String)
    /** Temporarily override the WebView User-Agent string. Pass null to restore default. */
    fun setUserAgent(ua: String?)
}

/**
 * One implementation per supported platform. Each handler owns all platform-specific
 * logic: URL detection, media extraction, download headers.
 *
 * To add a new platform:
 *   1. Create a class in the platform/ package implementing this interface.
 *   2. Register it in the handlers list in MainActivity.
 *   3. Add its domains to AndroidManifest.xml and network_security_config.xml.
 */
interface PlatformHandler {
    /** Stable identifier used as MediaItem.source and for handler lookup. */
    val platformId: String
    /** Subfolder name under Downloads/ where files are saved. */
    val folderName: String
    /** URL to load when the user taps the Login button. */
    val loginUrl: String
    /**
     * Domain to read cookies from when building download request headers.
     * Return null if no cookie is needed for downloads (e.g. public CDNs).
     */
    val cookieDomain: String? get() = null

    /** Return true if this handler should own the given URL. */
    fun matches(url: String): Boolean

    /** Called on the main thread when the WebView finishes loading a page. */
    fun onPageFinished(url: String, ctx: HandlerContext)

    /** Called for every WebView sub-resource request. Used to capture CDN media URLs. */
    fun onInterceptRequest(url: String, ctx: HandlerContext)

    /**
     * Called from shouldInterceptRequest with the full request object.
     * Return a [WebResourceResponse] to proxy the request (handler reads the body
     * and feeds the same bytes back to the WebView), or null to let the WebView
     * make the request normally (default).
     *
     * Use this when you need to read the response body of a sub-resource request
     * (e.g. an API call whose response contains media URLs).
     */
    fun onInterceptRequestFull(request: WebResourceRequest, ctx: HandlerContext): WebResourceResponse? = null

    /**
     * Return HTTP headers to use when downloading [itemUrl].
     * [cookie] is pre-fetched from CookieManager using [cookieDomain]; may be null.
     */
    fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String>

    /**
     * Called from loadUrl() the moment a new URL is committed to this handler,
     * before the WebView starts loading. Use this to pre-initialize any per-request
     * state (e.g. deciding whether CDN interception should be active).
     */
    fun onUrlCommitted(url: String) {}

    /** Optional: called when the JS bridge fires foundAppId(). Relevant for Instagram. */
    fun onFoundAppId(id: String) {}

    /**
     * Return the User-Agent string the WebView should use for this URL,
     * or null to use the system default. Called from loadUrl() so the UA
     * is set correctly before the WebView starts the request.
     * Default: null (system default / mobile UA).
     */
    fun preferredUserAgent(url: String): String? = null
}
