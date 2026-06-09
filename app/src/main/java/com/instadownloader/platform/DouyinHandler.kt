package com.instadownloader.platform

import com.instadownloader.HandlerContext
import com.instadownloader.PlatformHandler
import com.instadownloader.WEB_UA
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val DOUYIN_MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

/**
 * Handles 抖音 (Douyin) video and image-set downloads.
 *
 * Extraction strategy (two layers):
 *
 * Primary — network interception via onInterceptRequest:
 *   shouldInterceptRequest fires at the network level when the page JS calls
 *   aweme/v1/web/aweme/detail. We make a parallel HTTP request with the same URL
 *   (which already contains all auth tokens as query params) plus WebView cookies,
 *   parse the JSON, and report media. This works regardless of when onPageFinished
 *   fires relative to the API call.
 *
 * Fallback — JS fetch() hook via onPageFinished:
 *   For cases where the network interception misses (e.g. SPA in-page navigation),
 *   we install a fetch() wrapper that catches any subsequent aweme/detail calls.
 *   The guard flag is reset on each new load so the hook is always fresh.
 */
class DouyinHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = "https://www.douyin.com/login"
    override val cookieDomain: String? = null

    companion object {
        fun isDouyinUrl(url: String) =
            url.contains("douyin.com") || url.contains("v.douyin.com")
    }

    override fun matches(url: String) = isDouyinUrl(url)

    // Reset the JS hook guard whenever a new URL is committed so the hook is
    // always freshly installed on the next onPageFinished.
    override fun onUrlCommitted(url: String) { }

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.setStatus("正在提取抖音视频…")
        // Reset guard then re-install hook — covers SPA in-page navigations that
        // the network interceptor might miss on subsequent video views.
        ctx.injectJs("window.__douyinHooked = false;")
        ctx.injectJs(fetchHookJs)
    }

    /**
     * Primary extraction path. Called from shouldInterceptRequest (background thread).
     * Detects the aweme/detail API URL, fires a parallel HTTP request with the
     * same URL + WebView cookies, and reports media directly from the JSON response.
     * Returns immediately so the WebView's own request is not blocked.
     */
    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        if (!url.contains("aweme/v1/web/aweme/detail")) return
        Thread {
            try {
                val cookie = ctx.getCookie("https://www.douyin.com") ?: ""
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod  = "GET"
                conn.connectTimeout = 10_000
                conn.readTimeout    = 15_000
                conn.setRequestProperty("User-Agent",     WEB_UA)
                conn.setRequestProperty("Cookie",         cookie)
                conn.setRequestProperty("Referer",        "https://www.douyin.com/")
                conn.setRequestProperty("Accept",         "application/json, */*")
                conn.setRequestProperty("Accept-Language","zh-CN,zh;q=0.9")
                if (conn.responseCode == 200) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    parseAndReport(json, ctx)
                }
            } catch (_: Exception) {}
        }.start()
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to DOUYIN_MOBILE_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    // ── Response parsing ──────────────────────────────────────────────────────

    private fun parseAndReport(data: JSONObject, ctx: HandlerContext) {
        try {
            if (data.optInt("status_code", -1) != 0) return
            val detail = data.getJSONObject("aweme_detail")
            val images = detail.optJSONArray("images")
            if (images != null && images.length() > 0) {
                for (i in 0 until images.length()) {
                    val urlList = images.getJSONObject(i).optJSONArray("url_list") ?: continue
                    if (urlList.length() > 0)
                        ctx.reportMedia("image", urlList.getString(urlList.length() - 1))
                }
            } else {
                val bitRates = detail.getJSONObject("video").getJSONArray("bit_rate")
                var bestUrl = ""; var bestRate = 0
                for (i in 0 until bitRates.length()) {
                    val item = bitRates.getJSONObject(i)
                    val rate = item.getInt("bit_rate")
                    if (rate > bestRate) { bestRate = rate; bestUrl = item.getJSONObject("play_addr").getJSONArray("url_list").getString(0) }
                }
                if (bestUrl.isNotEmpty()) ctx.reportMedia("video", bestUrl)
            }
        } catch (_: Exception) {}
    }

    // ── JS fallback hook ──────────────────────────────────────────────────────

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
                            ? args[0] : (args[0] && args[0].url) || '';
                        if (reqUrl.indexOf('aweme/v1/web/aweme/detail') === -1) return res;
                        res.clone().json().then(function(data) {
                            try {
                                var detail = data.aweme_detail;
                                if (detail.images && detail.images.length > 0) {
                                    detail.images.forEach(function(img) {
                                        var list = img.url_list;
                                        if (list && list.length > 0)
                                            AndroidBridge.foundMedia('image', list[list.length - 1]);
                                    });
                                } else {
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
