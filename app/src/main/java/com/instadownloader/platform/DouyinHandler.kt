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
 * Strategy (mirrors InstagramHandler / TwitterHandler pattern):
 *   onPageFinished sees the final URL (e.g. douyin.com/video/7293...) after the
 *   short-link redirect has already been followed by the WebView. We extract the
 *   numeric ID and call aweme/detail ourselves with the WebView's cookies.
 *   No JS hook or shouldInterceptRequest needed — the API works with valid session
 *   cookies (ttwid, __ac_signature, s_v_web_id) even without X-Bogus.
 */
class DouyinHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = "https://www.douyin.com/login"
    override val cookieDomain: String? = null

    companion object {
        fun isDouyinUrl(url: String) =
            url.contains("douyin.com") || url.contains("v.douyin.com")

        /** Extract numeric ID from douyin.com/video/ID or douyin.com/note/ID. */
        fun videoIdFrom(url: String): String? =
            Regex("""douyin\.com/(?:video|note)/(\d+)""").find(url)?.groupValues?.get(1)
    }

    override fun matches(url: String) = isDouyinUrl(url)

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.setStatus("正在提取抖音视频…")

        val videoId = videoIdFrom(url)
        if (videoId != null) {
            // Primary path: call API directly with the video ID we extracted from the URL.
            ctx.runBackground { fetchByVideoId(videoId, ctx) }
        }
        // Secondary fallback: JS fetch() hook catches aweme/detail on SPA in-page navigation
        // (e.g. user taps another video without a full page reload).
        ctx.injectJs("window.__douyinHooked = false;")
        ctx.injectJs(fetchHookJs)
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        // Not used — extraction is driven by onPageFinished + direct API call.
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to DOUYIN_MOBILE_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    // ── Direct API call ───────────────────────────────────────────────────────

    private fun fetchByVideoId(videoId: String, ctx: HandlerContext) {
        try {
            val cookie = ctx.getCookie("https://www.douyin.com") ?: ""
            // Basic params sufficient for the API with valid session cookies.
            // X-Bogus is omitted — the endpoint accepts requests without it when
            // the cookie contains a valid ttwid / __ac_signature.
            val apiUrl = "https://www.douyin.com/aweme/v1/web/aweme/detail/" +
                "?device_platform=webapp&aid=6383&channel=channel_pc_web" +
                "&aweme_id=$videoId" +
                "&pc_client_type=1&version_code=190500&version_name=19.5.0" +
                "&cookie_enabled=true&screen_width=1280&screen_height=720" +
                "&browser_language=zh-CN&browser_platform=Win32" +
                "&browser_name=Chrome&browser_version=120.0.0.0" +
                "&browser_online=true&engine_name=Blink&engine_version=120.0.0.0" +
                "&os_name=Windows&os_version=10&cpu_core_num=8&device_memory=8" +
                "&platform=PC&downlink=10&effective_type=4g&round_trip_time=100"

            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.requestMethod  = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout    = 15_000
            conn.setRequestProperty("User-Agent",      WEB_UA)
            conn.setRequestProperty("Cookie",          cookie)
            conn.setRequestProperty("Referer",         "https://www.douyin.com/video/$videoId")
            conn.setRequestProperty("Accept",          "application/json, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")

            if (conn.responseCode == 200) {
                val json = JSONObject(conn.inputStream.bufferedReader().readText())
                parseAndReport(json, ctx)
            }
        } catch (_: Exception) {}
    }

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
                    if (rate > bestRate) {
                        bestRate = rate
                        bestUrl = item.getJSONObject("play_addr")
                            .getJSONArray("url_list").getString(0)
                    }
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
