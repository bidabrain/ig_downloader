package com.mediasave.platform

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.mediasave.HandlerContext
import com.mediasave.PlatformHandler
import com.mediasave.WEB_UA
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

private const val DOUYIN_MOBILE_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_6 like Mac OS X) " +
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.6 Mobile/15E148 Safari/604.1"

private const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * Handles 抖音 (Douyin) video and image-set downloads.
 *
 * Strategy:
 *  1. Short link → iesdouyin.com/share/video/ID
 *  2. onPageFinished: switch UA to DESKTOP_UA, navigate to www.douyin.com/video/ID
 *  3. onInterceptRequestFull catches the MAIN FRAME HTML request for douyin.com/video/ID,
 *     fetches the HTML ourselves with desktop headers, and injects a fingerprint-spoof
 *     <script> at the very top of <head> — before any Douyin JS runs.
 *     The spoof sets navigator.platform="Win32", maxTouchPoints=0, etc. so the SPA
 *     thinks it is running in a desktop browser and does NOT redirect to m.douyin.com.
 *  4. The SPA calls aweme/v1/web/aweme/detail — we intercept that too and parse media URLs.
 *  5. Fallback: if aweme/detail never fires, auto-play JS on the page captures CDN URLs.
 */
class DouyinHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = "https://www.douyin.com/login"
    override val cookieDomain: String? = "https://www.douyin.com"

    // Prevent infinite redirect loops: set to true after the first desktop attempt.
    private var desktopAttempted = false

    companion object {
        fun isDouyinUrl(url: String) =
            url.contains("douyin.com") || url.contains("iesdouyin.com")

        fun videoIdFrom(url: String): String? =
            Regex("""(?:douyin\.com/(?:video|note)|iesdouyin\.com/share/(?:video|note))/(\d+)""")
                .find(url)?.groupValues?.get(1)

        // Injected as the very first script inside <head> — overrides mobile fingerprints
        // before any Douyin JS can detect them and redirect to m.douyin.com.
        val FINGERPRINT_SPOOF_SCRIPT = """
            <script>(function(){
                var def = function(obj, key, val) {
                    try {
                        Object.defineProperty(obj, key, {
                            get: function() { return val; },
                            configurable: true
                        });
                    } catch(e) {}
                };
                def(navigator, 'platform',       'Win32');
                def(navigator, 'userAgent',       'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
                def(navigator, 'appVersion',      '5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36');
                def(navigator, 'maxTouchPoints',  0);
                def(navigator, 'vendor',          'Google Inc.');
                def(navigator, 'webdriver',       false);
                def(screen,    'width',           1920);
                def(screen,    'height',          1080);
                def(screen,    'availWidth',      1920);
                def(screen,    'availHeight',     1040);
                def(window,    'devicePixelRatio', 1);
                def(window,    'outerWidth',      1920);
                def(window,    'outerHeight',     1080);
                try { delete window.ontouchstart; } catch(e) {}
                try { def(window, 'ontouchstart', undefined); } catch(e) {}
                if (!window.chrome) {
                    try { def(window, 'chrome', { runtime: {}, app: {}, csi: function(){} }); } catch(e) {}
                }
            })();</script>
        """.trimIndent()
    }

    override fun matches(url: String) = isDouyinUrl(url)

    // Use desktop UA when loading www.douyin.com so the SPA's sub-resource requests
    // (including aweme/detail) carry a desktop User-Agent header, and navigator.userAgent
    // is already desktop-valued before the spoof script runs.
    override fun preferredUserAgent(url: String): String? =
        if (url.contains("www.douyin.com")) DESKTOP_UA else null

    // Reset the desktop-attempted flag whenever a fresh short link starts loading.
    override fun onUrlCommitted(url: String) {
        if (url.contains("v.douyin.com") || url.contains("iesdouyin.com")) {
            desktopAttempted = false
        }
    }

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.setStatus("正在提取抖音视频…")
        Log.d("DouyinHandler", "onPageFinished url=$url")

        val videoId = videoIdFrom(url)
        Log.d("DouyinHandler", "videoId=$videoId desktopAttempted=$desktopAttempted")

        when {
            videoId != null && (url.contains("iesdouyin.com") || url.contains("m.douyin.com")) -> {
                val isNote = url.contains("/share/note/") || url.contains("/note/")
                Log.d("DouyinHandler", "iesdouyin: videoId=$videoId isNote=$isNote desktopAttempted=$desktopAttempted")

                // Primary extraction: read _ROUTER_DATA that the SSR page embeds.
                // Works for both video posts (play_addr) and image/note posts (images array).
                ctx.injectJs(routerDataJs)

                if (!desktopAttempted && !isNote) {
                    desktopAttempted = true
                    // Give the JS 800 ms to report media, then switch the WebView to the
                    // desktop player page — without clearing capturedMedia.
                    ctx.postDelayed(800) {
                        Log.d("DouyinHandler", "navigateForDisplay → www.douyin.com/video/$videoId")
                        ctx.navigateForDisplay("https://www.douyin.com/video/$videoId")
                    }
                }
            }
            videoId != null && url.contains("www.douyin.com") -> {
                // SPA loaded with our spoofed fingerprints.
                // Inject fetch hook as extra fallback (in case intercept missed the response).
                Log.d("DouyinHandler", "douyin.com SPA loaded, injecting fetch hook")
                ctx.injectJs("window.__douyinHooked = false;")
                ctx.injectJs(fetchHookJs)
                // Also try auto-play in case aweme/detail never fires.
                ctx.postDelayed(3000) { ctx.injectJs(autoPlayJs) }
            }
        }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        Log.d("DouyinReq", url)

        // ── Capture video CDN stream URLs ─────────────────────────────────────────
        val isVideoCdn = url.contains("douyinvod.com") ||
            url.contains("bytecdn.cn") ||
            url.contains("ixigua.com") ||
            url.contains("toutiaoimg.com") ||
            url.contains("tiktokcdn.com") ||
            url.contains("amemv.com")
        if (isVideoCdn) {
            val staticExt = setOf(".js", ".css", ".html", ".png", ".jpg", ".ico", ".gif", ".svg", ".woff", ".woff2", ".ttf")
            val isStatic = staticExt.any { url.contains(it) }
            if (!isStatic) {
                val cleanUrl = if (url.contains("?")) {
                    val base = url.substringBefore("?")
                    val kept = url.substringAfter("?").split("&").filter { param ->
                        param.substringBefore("=").lowercase() !in
                            setOf("range", "eof", "bstart", "logo_name", "logo_type", "media_type")
                    }.joinToString("&")
                    if (kept.isEmpty()) base else "$base?$kept"
                } else url
                Log.d("DouyinHandler", "CDN video captured url=${cleanUrl.take(120)}")
                ctx.reportMedia("video", cleanUrl)
            }
            return
        }

        // NOTE: douyinpic.com CDN image interception was removed.
        // It captured images from ALL posts visible on the page (recommended feed, etc.),
        // not just the target post. Images are now extracted via aweme/detail API or
        // RENDER_DATA parsing, which give us only the target post's images.
    }

    /**
     * Two interception modes:
     *  A) Main frame HTML for www.douyin.com/video/ID → inject fingerprint-spoof script.
     *  B) aweme/v1/web/aweme/detail or iesdouyin.com/web/api/v2/aweme/post → proxy + parse.
     */
    override fun onInterceptRequestFull(request: WebResourceRequest, ctx: HandlerContext): WebResourceResponse? {
        val url = request.url.toString()

        // ── Mode A: inject fingerprint spoof into the desktop page HTML ───────────
        if (request.isForMainFrame &&
            (url.contains("www.douyin.com/video/") || url.contains("www.douyin.com/note/"))) {
            return fetchAndSpoof(url, ctx)
        }

        // ── Mode B: proxy API calls ───────────────────────────────────────────────
        val isPost     = url.contains("web/api/v2/aweme/post")
        val isDetail   = url.contains("aweme/v1/web/aweme/detail")
        val isItemInfo = url.contains("web/api/v2/aweme/iteminfo")
        if (!isPost && !isDetail && !isItemInfo) return null
        Log.d("DouyinHandler", "intercepting API url=$url")
        return proxyApiRequest(request, url, isPost, isItemInfo, ctx)
    }

    // ── Fingerprint-spoof HTML injection ─────────────────────────────────────────

    /**
     * Fetch the douyin.com video page HTML ourselves (with desktop UA + cookies),
     * inject a fingerprint-spoof <script> before any other script, strip CSP,
     * and return the modified response to the WebView.
     *
     * This ensures navigator.platform / maxTouchPoints / etc. are overridden before
     * Douyin's SPA code runs, preventing the JS-level redirect to m.douyin.com.
     */
    private fun fetchAndSpoof(url: String, ctx: HandlerContext): WebResourceResponse? {
        Log.d("DouyinHandler", "fetchAndSpoof url=$url")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 15_000
            conn.readTimeout    = 20_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent",                DESKTOP_UA)
            conn.setRequestProperty("Accept",                    "text/html,application/xhtml+xml,*/*;q=0.8")
            conn.setRequestProperty("Accept-Language",           "zh-CN,zh;q=0.9,en;q=0.8")
            conn.setRequestProperty("Referer",                   "https://www.douyin.com/")
            conn.setRequestProperty("Sec-Fetch-Dest",            "document")
            conn.setRequestProperty("Sec-Fetch-Mode",            "navigate")
            conn.setRequestProperty("Sec-Fetch-Site",            "none")
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1")
            val cookie = ctx.getCookie("https://www.douyin.com")
            if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)

            val code = conn.responseCode
            val finalUrl = conn.url.toString()
            Log.d("DouyinHandler", "fetchAndSpoof code=$code finalUrl=$finalUrl")
            if (code != 200) return null

            var html = conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
            Log.d("DouyinHandler", "HTML len=${html.length} hasHead=${html.contains("<head", ignoreCase = true)}")

            // ── Try to extract media URLs from RENDER_DATA before serving the page ──
            // The SPA gets video/image data from SSR RENDER_DATA and never calls
            // aweme/detail, so we must parse it here from the fetched HTML.
            ctx.runBackground { tryParseRenderData(html, ctx) }

            // Inject spoof script right after <head> tag so it runs first.
            val idx = html.indexOf("<head>").takeIf { it >= 0 }
                ?: html.indexOf("<HEAD>").takeIf { it >= 0 }
                ?: html.indexOf("<head ").takeIf { it >= 0 }
            if (idx != null) {
                val insertAt = html.indexOf('>', idx) + 1   // after the full <head ...> tag
                html = html.substring(0, insertAt) + FINGERPRINT_SPOOF_SCRIPT + html.substring(insertAt)
                Log.d("DouyinHandler", "injected spoof at position=$insertAt")
            } else {
                html = FINGERPRINT_SPOOF_SCRIPT + html
                Log.d("DouyinHandler", "no <head> found — prepended spoof")
            }

            // Strip CSP so our inline script isn't blocked.
            val headers = conn.headerFields
                .filterKeys { k -> k != null }
                .filterKeys { k -> k.lowercase() !in setOf(
                    "content-security-policy",
                    "content-security-policy-report-only",
                    "content-encoding",   // we already decoded
                    "transfer-encoding"   // we have the full body
                )}
                .mapValues { it.value.joinToString(", ") }

            val bytes = html.toByteArray(Charsets.UTF_8)
            Log.d("DouyinHandler", "returning spoofed HTML len=${bytes.size}")
            WebResourceResponse("text/html", "utf-8", 200, "OK", headers,
                ByteArrayInputStream(bytes))
        } catch (e: Exception) {
            Log.e("DouyinHandler", "fetchAndSpoof exception", e)
            null   // fall back to WebView fetching the page itself (no spoof)
        }
    }

    // ── API request proxy ─────────────────────────────────────────────────────

    private fun proxyApiRequest(
        request: WebResourceRequest, url: String, isPost: Boolean, isItemInfo: Boolean = false, ctx: HandlerContext
    ): WebResourceResponse? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod  = "GET"
        conn.connectTimeout = 12_000
        conn.readTimeout    = 15_000
        conn.instanceFollowRedirects = true
        val skipHeaders = setOf("accept-encoding", "host")
        request.requestHeaders?.forEach { (k, v) ->
            if (k.lowercase() !in skipHeaders)
                try { conn.setRequestProperty(k, v) } catch (_: Exception) {}
        }
        val reqDomain = "${request.url.scheme}://${request.url.host}"
        val cookie = ctx.getCookie(reqDomain)
        if (!cookie.isNullOrBlank()) conn.setRequestProperty("Cookie", cookie)
        Log.d("DouyinHandler", "proxy cookie len=${cookie?.length ?: 0} domain=$reqDomain")

        val code = conn.responseCode
        Log.d("DouyinHandler", "proxy code=$code type=${conn.contentType} len=${conn.contentLength}")
        if (code == 200) {
            val bytes = conn.inputStream.readBytes()
            Log.d("DouyinHandler", "proxy body len=${bytes.size} prefix=${String(bytes).take(200)}")
            try {
                val json = JSONObject(String(bytes))
                when {
                    isItemInfo -> parseItemInfoResponse(json, ctx)
                    isPost     -> parsePostResponse(json, ctx)
                    else       -> parseAndReport(json, ctx)
                }
            } catch (e: Exception) {
                Log.e("DouyinHandler", "parse exception", e)
            }
            val mimeType = conn.contentType?.substringBefore(";")?.trim() ?: "application/json"
            val headers  = conn.headerFields.filterKeys { it != null }
                .mapValues { it.value.joinToString(", ") }
            WebResourceResponse(mimeType, "utf-8", code, conn.responseMessage ?: "OK",
                headers, ByteArrayInputStream(bytes))
        } else null
    } catch (e: Exception) {
        Log.e("DouyinHandler", "proxyApiRequest exception", e)
        null
    }

    // ── RENDER_DATA parser ────────────────────────────────────────────────────

    /**
     * The douyin.com SPA embeds full aweme data in a RENDER_DATA <script> tag (SSR).
     * When the SPA uses this data it never calls aweme/detail, so we parse it here
     * directly from the HTML we already fetched in fetchAndSpoof.
     *
     * Structure (camelCase keys from frontend, snake_case inside media objects):
     *   { ..., "<route-key>": { awemeId: "...", aweme: { statusCode: 0, detail: { ... } } } }
     */
    private fun tryParseRenderData(html: String, ctx: HandlerContext) {
        try {
            val match = Regex(
                """<script id="RENDER_DATA" type="application/json">(.*?)</script>"""
            ).find(html) ?: run {
                Log.d("DouyinHandler", "RENDER_DATA not found in HTML")
                return
            }
            val data = JSONObject(java.net.URLDecoder.decode(match.groupValues[1], "UTF-8"))
            Log.d("DouyinHandler", "RENDER_DATA keys=${data.keys().asSequence().toList().take(8)}")

            // Find the route-key object that has awemeId
            var awemeObj: JSONObject? = null
            outer@ for (k in data.keys()) {
                val v = data.optJSONObject(k) ?: continue
                if (v.has("awemeId")) { awemeObj = v.optJSONObject("aweme"); break }
                for (k2 in v.keys()) {
                    val v2 = v.optJSONObject(k2) ?: continue
                    if (v2.has("awemeId")) {
                        Log.d("DouyinHandler", "awemeId found at $k.$k2")
                        awemeObj = v2.optJSONObject("aweme"); break@outer
                    }
                }
            }

            if (awemeObj == null) {
                Log.d("DouyinHandler", "RENDER_DATA: awemeId not found")
                return
            }
            val statusCode = awemeObj.optInt("statusCode", -1)
            Log.d("DouyinHandler", "RENDER_DATA statusCode=$statusCode")
            if (statusCode != 0) return

            val detail = awemeObj.optJSONObject("detail") ?: run {
                Log.d("DouyinHandler", "RENDER_DATA: no detail field")
                return
            }
            val awemeType = detail.optInt("awemeType", 0)
            Log.d("DouyinHandler", "RENDER_DATA awemeType=$awemeType detail keys=${detail.keys().asSequence().toList().take(10)}")

            if (awemeType != 0) {
                // 图文 / note post — extract image URLs
                val images = detail.optJSONArray("images")
                if (images != null && images.length() > 0) {
                    for (i in 0 until images.length()) {
                        val urlList = images.getJSONObject(i).optJSONArray("url_list") ?: continue
                        if (urlList.length() > 0)
                            ctx.reportMedia("image", urlList.getString(urlList.length() - 1))
                    }
                    Log.d("DouyinHandler", "RENDER_DATA: reported ${images.length()} images")
                    ctx.runOnUi { ctx.setStatus("找到 ${images.length()} 张图片，点击下载全部保存") }
                    return
                }
            }
            // Video post
            val video = detail.optJSONObject("video") ?: run {
                Log.d("DouyinHandler", "RENDER_DATA: no video field"); return
            }
            val bitRates = video.optJSONArray("bit_rate")
            if (bitRates != null && bitRates.length() > 0) {
                var bestUrl = ""; var bestRate = 0
                for (i in 0 until bitRates.length()) {
                    val item = bitRates.getJSONObject(i)
                    val rate = item.optInt("bit_rate", 0)
                    if (rate > bestRate) {
                        bestRate = rate
                        bestUrl = item.optJSONObject("play_addr")
                            ?.optJSONArray("url_list")?.optString(0) ?: ""
                    }
                }
                if (bestUrl.isNotEmpty()) {
                    ctx.reportMedia("video", bestUrl)
                    Log.d("DouyinHandler", "RENDER_DATA: reported video via bit_rate")
                    ctx.runOnUi { ctx.setStatus("找到视频，点击下载全部保存") }
                    return
                }
            }
            val playUrl = video.optJSONObject("play_addr")
                ?.optJSONArray("url_list")?.optString(0) ?: ""
            if (playUrl.isNotEmpty()) {
                ctx.reportMedia("video", playUrl)
                Log.d("DouyinHandler", "RENDER_DATA: reported video via play_addr")
                ctx.runOnUi { ctx.setStatus("找到视频，点击下载全部保存") }
                return
            }
            Log.d("DouyinHandler", "RENDER_DATA: no playable URL found in detail")
        } catch (e: Exception) {
            Log.e("DouyinHandler", "tryParseRenderData exception", e)
        }
    }

    // ── iteminfo direct fetch ─────────────────────────────────────────────────
    // Called proactively when we have a videoId from iesdouyin.com. This API returns
    // the full aweme detail (including play URLs) for a specific item, unlike aweme/post
    // which returns a user's post list and omits play URLs.
    private fun tryItemInfoFetch(videoId: String, cookie: String, ctx: HandlerContext): Boolean {
        val url = "https://www.iesdouyin.com/web/api/v2/aweme/iteminfo/?item_ids=$videoId"
        Log.d("DouyinHandler", "tryItemInfoFetch videoId=$videoId cookie_len=${cookie.length}")
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000
            conn.setRequestProperty("User-Agent",      WEB_UA)
            conn.setRequestProperty("Referer",         "https://www.iesdouyin.com/")
            conn.setRequestProperty("Accept",          "application/json, */*")
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)

            val code = conn.responseCode
            Log.d("DouyinHandler", "tryItemInfoFetch code=$code")
            if (code != 200) return false

            val body = conn.inputStream.bufferedReader().readText()
            Log.d("DouyinHandler", "tryItemInfoFetch body len=${body.length} prefix=${body.take(200)}")
            val json  = JSONObject(body)
            val items = json.optJSONArray("item_list") ?: run {
                Log.d("DouyinHandler", "tryItemInfoFetch: no item_list"); return false
            }
            if (items.length() == 0) { Log.d("DouyinHandler", "tryItemInfoFetch: empty item_list"); return false }

            val item = items.getJSONObject(0)
            val hasVideo = item.optJSONObject("video")?.let { v ->
                (v.optJSONObject("play_addr")?.optJSONArray("url_list")?.length()     ?: 0) > 0 ||
                (v.optJSONObject("download_addr")?.optJSONArray("url_list")?.length() ?: 0) > 0 ||
                (v.optJSONArray("bit_rate")?.length()                                 ?: 0) > 0
            } ?: false
            val hasImages = (item.optJSONArray("images")?.length() ?: 0) > 0

            Log.d("DouyinHandler", "tryItemInfoFetch hasVideo=$hasVideo hasImages=$hasImages")
            if (!hasVideo && !hasImages) return false

            parseDetail(item, ctx)
            ctx.runOnUi { ctx.setStatus(if (hasImages) "找到 ${item.optJSONArray("images")!!.length()} 张图片，点击下载全部保存" else "找到视频，点击下载全部保存") }
            true
        } catch (e: Exception) {
            Log.e("DouyinHandler", "tryItemInfoFetch exception", e)
            false
        }
    }

    private fun parseItemInfoResponse(data: JSONObject, ctx: HandlerContext) {
        try {
            val items = data.optJSONArray("item_list") ?: return
            Log.d("DouyinHandler", "iteminfo items=${items.length()}")
            if (items.length() == 0) return
            parseDetail(items.getJSONObject(0), ctx)
            ctx.runOnUi { ctx.setStatus("找到媒体，点击下载全部保存") }
        } catch (e: Exception) {
            Log.e("DouyinHandler", "parseItemInfoResponse exception", e)
        }
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to DOUYIN_MOBILE_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    // ── JSON parsers ──────────────────────────────────────────────────────────

    private fun parseDetail(detail: JSONObject, ctx: HandlerContext) {
        val images = detail.optJSONArray("images")
        if (images != null && images.length() > 0) {
            Log.d("DouyinHandler", "parseDetail: found ${images.length()} images")
            for (i in 0 until images.length()) {
                val urlList = images.getJSONObject(i).optJSONArray("url_list") ?: continue
                if (urlList.length() > 0)
                    ctx.reportMedia("image", urlList.getString(urlList.length() - 1))
            }
            ctx.runOnUi { ctx.setStatus("找到 ${images.length()} 张图片，点击下载全部保存") }
            return
        }
        val video = detail.optJSONObject("video") ?: return
        val bitRates = video.optJSONArray("bit_rate")
        if (bitRates != null && bitRates.length() > 0) {
            var bestUrl = ""; var bestRate = 0
            for (i in 0 until bitRates.length()) {
                val item = bitRates.getJSONObject(i)
                val rate = item.optInt("bit_rate", 0)
                if (rate > bestRate) {
                    bestRate = rate
                    bestUrl  = item.optJSONObject("play_addr")
                        ?.optJSONArray("url_list")?.optString(0) ?: ""
                }
            }
            if (bestUrl.isNotEmpty()) { ctx.reportMedia("video", bestUrl); return }
        }
        val playUrl = video.optJSONObject("play_addr")
            ?.optJSONArray("url_list")?.optString(0) ?: ""
        if (playUrl.isNotEmpty()) { ctx.reportMedia("video", playUrl); return }
        val dlUrl = video.optJSONObject("download_addr")
            ?.optJSONArray("url_list")?.optString(0) ?: ""
        if (dlUrl.isNotEmpty()) ctx.reportMedia("video", dlUrl)
    }

    private fun parseAndReport(data: JSONObject, ctx: HandlerContext) {
        try {
            val statusCode = data.optInt("status_code", -1)
            Log.d("DouyinHandler", "aweme/detail status_code=$statusCode")
            if (statusCode != 0) return
            parseDetail(data.getJSONObject("aweme_detail"), ctx)
            ctx.runOnUi { ctx.setStatus("找到视频，点击下载全部保存") }
        } catch (e: Exception) {
            Log.e("DouyinHandler", "parseAndReport exception", e)
        }
    }

    private fun parsePostResponse(data: JSONObject, ctx: HandlerContext) {
        try {
            val list = data.optJSONArray("aweme_list") ?: return
            Log.d("DouyinHandler", "aweme/post list length=${list.length()}")
            for (i in 0 until list.length()) {
                val item      = list.getJSONObject(i)
                val video     = item.optJSONObject("video")
                val playUrls  = video?.optJSONObject("play_addr")?.optJSONArray("url_list")?.length() ?: 0
                val dlUrls    = video?.optJSONObject("download_addr")?.optJSONArray("url_list")?.length() ?: 0
                val hasImages = item.optJSONArray("images")?.let { it.length() > 0 } ?: false
                Log.d("DouyinHandler", "item[$i] playUrls=$playUrls dlUrls=$dlUrls hasImages=$hasImages")
                if (video != null && (playUrls > 0 || dlUrls > 0 || hasImages)) {
                    parseDetail(item, ctx); return
                }
            }
        } catch (e: Exception) {
            Log.e("DouyinHandler", "parsePostResponse exception", e)
        }
    }

    // ── JS snippets ───────────────────────────────────────────────────────────

    /**
     * Reads window._ROUTER_DATA embedded by iesdouyin.com's SSR and reports
     * all media URLs via AndroidBridge.foundMedia().
     *
     * _ROUTER_DATA.loaderData["video_(id)/page"].videoInfoRes.item_list[0]:
     *   - images[]          → image/note post (url_list per image)
     *   - video.bit_rate[]  → video (highest quality play URL)
     *   - video.play_addr   → video fallback
     */
    private val routerDataJs = """
        (function() {
            try {
                var d = window._ROUTER_DATA;
                if (!d || !d.loaderData) return;
                var ld = d.loaderData;
                var pageData = null;
                var keys = ['video_(id)/page','note_(id)/page','slides_(id)/page','jx-video_(id)/page'];
                for (var ki = 0; ki < keys.length; ki++) {
                    var pg = ld[keys[ki]];
                    if (pg && pg.videoInfoRes) { pageData = pg; break; }
                }
                if (!pageData) return;
                var items = pageData.videoInfoRes.item_list;
                if (!items || items.length === 0) return;
                var item = items[0];
                // Image / note post
                var images = item.images;
                if (images && images.length > 0) {
                    for (var ii = 0; ii < images.length; ii++) {
                        var ul = images[ii].url_list;
                        if (ul && ul.length > 0)
                            AndroidBridge.foundMedia('image', ul[ul.length - 1]);
                    }
                    return;
                }
                // Video post
                var video = item.video;
                if (!video) return;
                var brs = video.bit_rate;
                if (brs && brs.length > 0) {
                    var best = null, bestR = -1;
                    for (var bi = 0; bi < brs.length; bi++) {
                        if ((brs[bi].bit_rate || 0) > bestR) {
                            bestR = brs[bi].bit_rate || 0; best = brs[bi];
                        }
                    }
                    if (best) {
                        var u = best.play_addr && best.play_addr.url_list && best.play_addr.url_list[0];
                        if (u) { AndroidBridge.foundMedia('video', u); return; }
                    }
                }
                var pu = video.play_addr && video.play_addr.url_list && video.play_addr.url_list[0];
                if (pu) { AndroidBridge.foundMedia('video', pu); return; }
                var du = video.download_addr && video.download_addr.url_list && video.download_addr.url_list[0];
                if (du) AndroidBridge.foundMedia('video', du);
            } catch(e) {}
        })();
    """.trimIndent()

    private val autoPlayJs = """
        (function() {
            document.querySelectorAll('video').forEach(function(v) {
                try { v.muted = true; v.play(); } catch(e) {}
            });
            ['[data-e2e="video-play"]','[class*="play-btn"]','[class*="playBtn"]',
             '[class*="play_btn"]','button[class*="play"]','[class*="videoPlay"]',
             '[class*="video-play"]'].forEach(function(sel) {
                try { document.querySelectorAll(sel).forEach(function(el) { el.click(); }); }
                catch(e) {}
            });
        })();
    """.trimIndent()

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
