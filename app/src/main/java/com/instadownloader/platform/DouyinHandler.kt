package com.instadownloader.platform

import com.instadownloader.HandlerContext
import com.instadownloader.PlatformHandler

// Douyin videos are served from CDN using a mobile UA for best compatibility.
private const val DOUYIN_MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

/**
 * Handles 抖音 (Douyin) video and image-set downloads.
 *
 * Strategy: inject a fetch() hook before the page runs. The hook intercepts the
 * aweme/v1/web/aweme/detail API response (which Douyin's own page JS triggers),
 * parses the JSON, and reports the highest-bitrate video or all image URLs.
 *
 * Cookie handling: WebView's CookieManager automatically persists and refreshes
 * Douyin's tokens (msToken, ttwid, __ac_signature) on each page load because
 * Douyin's own JS runs in the WebView and updates them. No manual refresh needed.
 */
class DouyinHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = "https://www.douyin.com/login"
    override val cookieDomain: String? = null  // CDN URLs are public; no cookie for download

    companion object {
        fun isDouyinUrl(url: String) =
            url.contains("douyin.com") || url.contains("v.douyin.com")
    }

    override fun matches(url: String) = isDouyinUrl(url)

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.setStatus("正在提取抖音视频…")
        // Inject the fetch hook immediately; the API call fires shortly after page load.
        ctx.injectJs(fetchHookJs)
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        // The fetch hook covers API interception; CDN capture is not needed.
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to DOUYIN_MOBILE_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    // ── JS hook ───────────────────────────────────────────────────────────────

    /**
     * Wraps window.fetch() to watch for the aweme/detail API response.
     * - Videos: picks the entry with the highest bit_rate from bit_rate[].
     * - Image sets: reports each image's last URL in url_list (typically the largest).
     *
     * The guard flag __douyinHooked prevents double-installation on SPA re-renders.
     */
    private val fetchHookJs = """
        (function() {
            if (window.__douyinHooked) return;
            window.__douyinHooked = true;
            var _fetch = window.fetch;
            window.fetch = function() {
                var args = arguments;
                return _fetch.apply(this, args).then(function(res) {
                    try {
                        var reqUrl = typeof args[0] === 'string'
                            ? args[0]
                            : (args[0] && args[0].url) || '';
                        if (reqUrl.indexOf('aweme/v1/web/aweme/detail') === -1) return res;
                        res.clone().json().then(function(data) {
                            try {
                                var detail = data.aweme_detail;
                                if (detail.images && detail.images.length > 0) {
                                    // Image set — report each image
                                    detail.images.forEach(function(img) {
                                        var list = img.url_list;
                                        if (list && list.length > 0)
                                            AndroidBridge.foundMedia('image', list[list.length - 1]);
                                    });
                                } else {
                                    // Video — pick highest bitrate
                                    var brs = detail.video.bit_rate;
                                    var best = brs.reduce(function(a, b) {
                                        return b.bit_rate > a.bit_rate ? b : a;
                                    });
                                    AndroidBridge.foundMedia('video', best.play_addr.url_list[0]);
                                }
                            } catch(e) {}
                        }).catch(function() {});
                    } catch(e) {}
                    return res;
                });
            };
        })();
    """.trimIndent()
}
