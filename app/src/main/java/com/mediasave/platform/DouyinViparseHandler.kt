package com.mediasave.platform

import android.util.Log
import com.mediasave.AppSettings
import com.mediasave.HandlerContext
import com.mediasave.PlatformHandler
import com.mediasave.WEB_UA
import org.json.JSONObject

private const val VIPARSE_HOME = "https://viparse.com/"

/**
 * Alternative Douyin resolver that delegates extraction to the third-party site viparse.com.
 *
 * Only active when [AppSettings.thirdPartyDouyin] is on; otherwise [matches] returns false and
 * the built-in [DouyinHandler] handles the URL unchanged.
 *
 * Flow:
 *  1. initialLoadUrl() redirects the WebView to viparse.com (keeping the Douyin URL as target).
 *  2. onPageFinished() on viparse.com injects JS that POSTs the Douyin URL to viparse's own
 *     /parse endpoint (using the page's CSRF token + session cookie) and reports the returned
 *     download_url back through AndroidBridge — feeding the normal download pipeline.
 *
 * Note: viparse limits anonymous use to ~6 parses/day; beyond that the user must log in on
 * viparse.com (reachable via the in-app Browser button).
 */
class DouyinViparseHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = VIPARSE_HOME
    override val cookieDomain: String? = null   // viparse returns signed CDN URLs; no cookie needed

    private var targetUrl: String = ""

    override fun matches(url: String) =
        AppSettings.thirdPartyDouyin && DouyinHandler.isDouyinUrl(url)

    override fun onUrlCommitted(url: String) { targetUrl = url }

    // Load viparse.com instead of the Douyin page.
    override fun initialLoadUrl(url: String): String = VIPARSE_HOME

    override fun preferredUserAgent(url: String): String? = WEB_UA

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        if (!url.contains("viparse.com")) return
        if (targetUrl.isBlank()) return
        Log.d("DouyinViparse", "injecting parse for $targetUrl")
        ctx.injectJs(parseJs(targetUrl))
        // viparse's /parse can take a few seconds; retry once in case the page wasn't ready.
        ctx.postDelayed(2500) { ctx.injectJs(parseJs(targetUrl)) }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) { /* not used */ }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to WEB_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    private fun parseJs(douyinUrl: String): String {
        val safeUrl = JSONObject.quote(douyinUrl)   // quoted, escaped JS string literal
        return """
            (function(){
                try {
                    if (window.__viparseDone) return;
                    var meta = document.querySelector('meta[name="csrf-token"]');
                    if (!meta) return;
                    fetch('/parse', {
                        method: 'POST',
                        headers: {
                            'X-CSRF-TOKEN': meta.getAttribute('content'),
                            'Content-Type': 'application/json',
                            'Accept': 'application/json'
                        },
                        body: JSON.stringify({ video_url: $safeUrl })
                    }).then(function(r){ return r.json(); })
                      .then(function(d){
                        try {
                            if (d && d.status === 'success' && d.data) {
                                var opts = d.data.quality_options || [];
                                if (opts.length > 0 && opts[0].download_url) {
                                    window.__viparseDone = true;
                                    AndroidBridge.foundMedia('video', opts[0].download_url);
                                }
                            }
                        } catch(e) {}
                    }).catch(function(){});
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
