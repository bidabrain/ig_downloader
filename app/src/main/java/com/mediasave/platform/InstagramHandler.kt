package com.mediasave.platform

import com.mediasave.HandlerContext
import com.mediasave.MediaItem
import com.mediasave.PlatformHandler
import com.mediasave.WEB_UA
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val IG_API_UA = "Mozilla/5.0 (Linux; Android 10; Pixel 7 XL)" +
    "Build/RP1A.20845.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Version/5.0 Chrome/117.0.5938.60 Mobile Safari/537.36 " +
    "Instagram 307.0.0.34.111"

class InstagramHandler : PlatformHandler {

    override val platformId = "instagram"
    override val folderName = "Instagram"
    override val loginUrl   = "https://www.instagram.com/accounts/login/"
    override val cookieDomain = "https://www.instagram.com"

    // Extracted from the page's embedded JSON; refreshed on every page load.
    var appId = "936619743392459"

    // True while viewing a post/reel page — CDN interception is skipped for these
    // because the GraphQL API already returns the best-quality URL.
    private var isPostPage = false

    companion object {
        fun shortcodeFrom(url: String): String? =
            Regex("instagram\\.com/(?:p|reel|reels)/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
    }

    override fun matches(url: String) = url.contains("instagram.com")

    // Pre-set isPostPage as soon as the URL is committed so CDN interception is
    // correctly gated from the very first sub-resource request, matching original behaviour.
    override fun onUrlCommitted(url: String) {
        isPostPage = shortcodeFrom(url) != null
    }

    override fun onFoundAppId(id: String) { if (id.isNotBlank()) appId = id }

    // Shortcode whose in-page API fetch is in flight; results for any other shortcode are stale.
    private var pendingShortcode: String? = null

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.injectJs(appIdFinderJs)
        val shortcode = shortcodeFrom(url)
        isPostPage = shortcode != null
        if (shortcode != null) {
            ctx.setStatus("Fetching media for post $shortcode…")
            // Primary path: call Instagram's API from inside the page so the request carries
            // the real session cookies and the page's LSD/DTSG tokens (mirrors ig-helper).
            pendingShortcode = shortcode
            ctx.injectJs(inPageFetchJs(shortcode))
            // Safety net in case the injected script never reports back.
            ctx.postDelayed(15_000) {
                if (pendingShortcode == shortcode) {
                    pendingShortcode = null
                    fetchNativeThenScan(shortcode, ctx)
                }
            }
        } else {
            ctx.setStatus("Page loaded — press Download All when ready")
            ctx.injectJs(mediaFinderJs)
            ctx.postDelayed(1200) { ctx.injectJs(mediaFinderJs) }
        }
    }

    override fun onBridgeData(key: String, data: String, ctx: HandlerContext) {
        if (key != "ig_item" && key != "ig_fail") return
        val shortcode = pendingShortcode ?: return
        val (sc, payload) = data.split('\n', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        if (sc != shortcode) return
        pendingShortcode = null

        val media = if (key == "ig_item") try {
            val item = JSONObject(payload)
            val username = item.optJSONObject("user")?.optString("username") ?: "instagram"
            mediaItemsFromV1Item(item, username, shortcode)
        } catch (_: Exception) { emptyList() } else emptyList()

        if (media.isNotEmpty()) {
            media.forEach { ctx.reportMedia(it.type, it.url) }
            ctx.setStatus("Found ${media.size} item(s) — press Download All to save")
        } else {
            fetchNativeThenScan(shortcode, ctx)
        }
    }

    private fun fetchNativeThenScan(shortcode: String, ctx: HandlerContext) {
        ctx.runBackground {
            val cookie = ctx.getCookie("https://www.instagram.com") ?: ""
            val media = tryGraphQlFetch(shortcode, cookie)
            ctx.runOnUi {
                if (media.isNotEmpty()) {
                    media.forEach { ctx.reportMedia(it.type, it.url) }
                    ctx.setStatus("Found ${media.size} item(s) — press Download All to save")
                } else {
                    ctx.setStatus("Scanning page data… tap Download when count appears")
                    ctx.injectJs(postMediaFinderJs)
                    ctx.postDelayed(2000) { ctx.injectJs(postMediaFinderJs) }
                    ctx.postDelayed(4000) { ctx.injectJs(postMediaFinderJs) }
                }
            }
        }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        if (isPostPage) return
        if (url.isBlank()) return
        val isIgCdn = url.contains("cdninstagram.com") || url.contains("fbcdn.net")
        if (!isIgCdn) return
        val isVideo = url.contains(".mp4") && !url.contains("thumbnail")
        val isImage = !isVideo &&
            (url.contains(".jpg") || url.contains(".webp")) &&
            (url.contains("_e35") || url.contains("_e15") || url.contains("1080x"))
        val type = when { isVideo -> "video"; isImage -> "image"; else -> return }
        ctx.reportMedia(type, url)
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        buildMap {
            put("User-Agent", WEB_UA)
            put("Referer", "https://www.instagram.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }

    // ── API fetching ─────────────────────────────────────────────────────────

    private fun tryGraphQlFetch(shortcode: String, cookie: String): List<MediaItem> {
        val ref = "https://www.instagram.com/p/$shortcode/"

        val url1 = "https://www.instagram.com/graphql/query/" +
            "?query_hash=2c4c2e343a8f64c625ba02b2aa12c7f8" +
            "&variables=%7B%22shortcode%22%3A%22$shortcode%22%7D"
        httpGet(url1, cookie, ref)?.let {
            val r = parseQueryHashResponse(it, shortcode); if (r.isNotEmpty()) return r
        }

        val url2 = "https://www.instagram.com/graphql/query/" +
            "?query_id=9496392173716084" +
            "&variables=%7B%22shortcode%22%3A%22$shortcode%22" +
            "%2C%22__relay_internal__pv__PolarisFeedShareMenurelayprovider%22%3Atrue" +
            "%2C%22__relay_internal__pv__PolarisIsLoggedInrelayprovider%22%3Atrue%7D"
        httpGet(url2, cookie, ref)?.let {
            val r = parseQueryIdResponse(it, shortcode); if (r.isNotEmpty()) return r
        }

        val url3 = "https://www.instagram.com/api/v1/media/shortcode/web_info/?shortcode=$shortcode"
        httpGet(url3, cookie, ref)?.let {
            val r = parseQueryIdResponse(it, shortcode); if (r.isNotEmpty()) return r
        }

        return emptyList()
    }

    private fun httpGet(url: String, cookie: String, referer: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000
            conn.setRequestProperty("User-Agent",       IG_API_UA)
            conn.setRequestProperty("Cookie",           cookie)
            conn.setRequestProperty("Referer",          referer)
            conn.setRequestProperty("X-Requested-With", "")
            conn.setRequestProperty("Accept",           "application/json, */*")
            conn.setRequestProperty("Accept-Language",  "en-US,en;q=0.9")
            conn.setRequestProperty("X-IG-App-ID",      appId)
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
        } catch (_: Exception) { null }
    }

    private fun parseQueryHashResponse(json: String, shortcode: String): List<MediaItem> = try {
        val root     = JSONObject(json)
        val resource = root.getJSONObject("data").getJSONObject("shortcode_media")
        val username = resource.optJSONObject("owner")?.optString("username") ?: "instagram"
        mediaItemsFromResource(resource, username, shortcode)
    } catch (_: Exception) { emptyList() }

    private fun parseQueryIdResponse(json: String, shortcode: String): List<MediaItem> {
        return try {
            val root  = JSONObject(json)
            val items = root.getJSONObject("data")
                .getJSONObject("xdt_api__v1__media__shortcode__web_info")
                .getJSONArray("items")
            if (items.length() == 0) return emptyList()
            val item     = items.getJSONObject(0)
            val username = item.optJSONObject("user")?.optString("username") ?: "instagram"
            mediaItemsFromV1Item(item, username, shortcode)
        } catch (_: Exception) { emptyList() }
    }

    private fun mediaItemsFromResource(resource: JSONObject, username: String, shortcode: String): List<MediaItem> {
        val results  = mutableListOf<MediaItem>()
        fun bestImage(res: JSONObject): String {
            val arr = res.optJSONArray("display_resources")
            return if (arr != null && arr.length() > 0)
                arr.getJSONObject(arr.length() - 1).optString("src")
            else res.optString("display_url")
        }
        when (resource.optString("__typename", "")) {
            "GraphVideo"   -> resource.optString("video_url").takeIf { it.isNotEmpty() }
                ?.let { results += MediaItem("video", it, "${username}_$shortcode.mp4") }
            "GraphImage"   -> bestImage(resource).takeIf { it.isNotEmpty() }
                ?.let { results += MediaItem("image", it, "${username}_$shortcode.jpg") }
            "GraphSidecar" -> {
                val edges = resource.optJSONObject("edge_sidecar_to_children")
                    ?.optJSONArray("edges") ?: return results
                for (i in 0 until edges.length()) {
                    val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                    val idx  = i + 1
                    if (node.optString("__typename") == "GraphVideo")
                        node.optString("video_url").takeIf { it.isNotEmpty() }
                            ?.let { results += MediaItem("video", it, "${username}_${shortcode}_$idx.mp4") }
                    else
                        bestImage(node).takeIf { it.isNotEmpty() }
                            ?.let { results += MediaItem("image", it, "${username}_${shortcode}_$idx.jpg") }
                }
            }
        }
        return results
    }

    private fun mediaItemsFromV1Item(item: JSONObject, username: String, shortcode: String): List<MediaItem> {
        val results = mutableListOf<MediaItem>()
        fun bestV1Image(node: JSONObject): String =
            node.optJSONObject("image_versions2")?.optJSONArray("candidates")
                ?.takeIf { it.length() > 0 }?.getJSONObject(0)?.optString("url") ?: ""

        val carouselMedia = item.optJSONArray("carousel_media")
        if (carouselMedia != null) {
            for (i in 0 until carouselMedia.length()) {
                val node = carouselMedia.getJSONObject(i); val idx = i + 1
                val videos = node.optJSONArray("video_versions")
                if (videos != null && videos.length() > 0)
                    videos.getJSONObject(0).optString("url").takeIf { it.isNotEmpty() }
                        ?.let { results += MediaItem("video", it, "${username}_${shortcode}_$idx.mp4") }
                else
                    bestV1Image(node).takeIf { it.isNotEmpty() }
                        ?.let { results += MediaItem("image", it, "${username}_${shortcode}_$idx.jpg") }
            }
        } else {
            val videos = item.optJSONArray("video_versions")
            if (videos != null && videos.length() > 0)
                videos.getJSONObject(0).optString("url").takeIf { it.isNotEmpty() }
                    ?.let { results += MediaItem("video", it, "${username}_$shortcode.mp4") }
            else
                bestV1Image(item).takeIf { it.isNotEmpty() }
                    ?.let { results += MediaItem("image", it, "${username}_$shortcode.jpg") }
        }
        return results
    }

    // ── JS snippets ───────────────────────────────────────────────────────────

    /**
     * Runs inside the post page and reports the post's v1 media item back via
     * AndroidBridge.postData("ig_item" | "ig_fail", "<shortcode>\n<json>").
     * 1. GraphQL PolarisPostRootQuery POST with the page's LSD/DTSG/SiteData tokens.
     * 2. /api/v1/media/<id>/info/ with the media id decoded from the shortcode.
     * Request shape follows SN-Koarashi/ig-helper getMediaList()/getMediaInfo().
     */
    private fun inPageFetchJs(shortcode: String) = """
        (function() {
            var SC = '$shortcode';
            function done(key, payload) {
                try { AndroidBridge.postData(key, SC + '\n' + (payload || '')); } catch (e) {}
            }
            function jsonScripts(sjsOnly) {
                return document.querySelectorAll(sjsOnly
                    ? 'script[type="application/json"][data-sjs]'
                    : 'script[type="application/json"]');
            }
            function findInScripts(re, sjsOnly) {
                var r = null;
                jsonScripts(sjsOnly).forEach(function(s) {
                    if (r) return;
                    var m = (s.textContent || '').match(re);
                    if (m) r = m[1];
                });
                return r;
            }
            function cookie(n) {
                var m = document.cookie.match(new RegExp('(?:^|; )' + n + '=([^;]*)'));
                return m ? decodeURIComponent(m[1]) : '';
            }
            function findConfig(data, name) {
                var stack = [data];
                while (stack.length) {
                    var node = stack.pop();
                    if (Array.isArray(node)) {
                        if (node[0] === name && node.length >= 3 && node[2] &&
                            typeof node[2] === 'object' && !Array.isArray(node[2])) return node[2];
                        for (var i = 0; i < node.length; i++) stack.push(node[i]);
                    } else if (node && typeof node === 'object') {
                        for (var k in node) stack.push(node[k]);
                    }
                }
                return null;
            }
            function siteData() {
                var r = null;
                jsonScripts(true).forEach(function(s) {
                    if (r) return;
                    var t = s.textContent || '';
                    if (t.indexOf('"SiteData",[],{') < 0) return;
                    try { r = findConfig(JSON.parse(t), 'SiteData'); } catch (e) {}
                });
                return r || {};
            }
            function rand6() {
                return Math.floor(Math.random() * 2147483648).toString(36).padStart(6, '0');
            }
            function mediaIdFromShortcode(sc) {
                var abc = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_';
                var id = BigInt(0);
                sc.substring(0, 11).split('').forEach(function(c) {
                    id = id * BigInt(64) + BigInt(abc.indexOf(c));
                });
                return id.toString();
            }

            var appId = findInScripts(/"APP_ID":"([0-9]+)"/, false) || '936619743392459';
            var lsd   = findInScripts(/\["LSD",\[\],\{"token":"([0-9a-z_-]+)"\}/i, true) || '';
            var dtsg  = findInScripts(/"DTSGInitialData",\[\],\{"token":"([0-9a-z_:-]+)"\}/i, true) || '';
            var bloks = findInScripts(/"WebBloksVersioningID",\[\],\{"versioningID":"([0-9a-z_-]+)"\}/i, true) || '';
            var csrf  = cookie('csrftoken');

            function viaGraphql() {
                var sd = siteData();
                var p = new URLSearchParams();
                p.append('av', cookie('ds_user_id') || '0');
                p.append('__d', 'www');
                p.append('__user', '0');
                p.append('__a', '1');
                p.append('__req', '1');
                p.append('__hs', sd.haste_session || '');
                p.append('dpr', String(sd.pr || window.devicePixelRatio || 1));
                p.append('__ccg', 'EXCELLENT');
                p.append('__rev', String(sd.client_revision || ''));
                p.append('__s', rand6() + ':' + rand6() + ':' + rand6());
                p.append('__hsi', String(sd.hsi || ''));
                p.append('__comet_req', String(sd.comet_env || ''));
                p.append('__crn', 'comet.igweb.PolarisFeedRoute');
                if (dtsg) {
                    p.append('fb_dtsg', dtsg);
                    var sum = 0;
                    for (var i = 0; i < dtsg.length; i++) sum += dtsg.charCodeAt(i);
                    p.append('jazoest', '2' + sum);
                }
                p.append('lsd', lsd);
                p.append('__spin_r', String(sd.__spin_r || ''));
                p.append('__spin_b', String(sd.__spin_b || ''));
                p.append('__spin_t', String(sd.__spin_t || ''));
                p.append('fb_api_caller_class', 'RelayModern');
                p.append('fb_api_req_friendly_name', 'PolarisPostRootQuery');
                p.append('server_timestamps', 'true');
                p.append('variables', JSON.stringify({
                    shortcode: SC,
                    __relay_internal__pv__PolarisShortDramaEnabledrelayprovider: true,
                    __relay_internal__pv__PolarisThreadsStyleCommentsEnabledrelayprovider: false,
                    __relay_internal__pv__PolarisMultiCaptionCarouselEnabledrelayprovider: true
                }));
                p.append('doc_id', '39422285384081769');
                return fetch('/graphql/query', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Content-Type': 'application/x-www-form-urlencoded',
                        'X-IG-App-ID': appId,
                        'X-CSRFToken': csrf,
                        'X-FB-Friendly-Name': 'PolarisPostRootQuery',
                        'X-FB-LSD': lsd,
                        'X-Root-Field-Name': 'xdt_api__v1__media__shortcode__web_info',
                        'X-Bloks-Version-ID': bloks,
                        'X-ASBD-ID': '359341'
                    },
                    body: p.toString()
                }).then(function(r) { return r.json(); }).then(function(j) {
                    return j.data.xdt_api__v1__media__shortcode__web_info.items[0] || null;
                });
            }

            function viaMediaInfo() {
                return fetch('/api/v1/media/' + mediaIdFromShortcode(SC) + '/info/', {
                    credentials: 'include',
                    headers: {
                        'X-IG-App-ID': appId,
                        'X-CSRFToken': csrf,
                        'X-ASBD-ID': '359341',
                        'X-IG-WWW-Claim': sessionStorage.getItem('www-claim-v2') || '0',
                        'X-Requested-With': 'XMLHttpRequest'
                    }
                }).then(function(r) { return r.json(); }).then(function(j) {
                    return (j.items && j.items[0]) || null;
                });
            }

            function attempt(fn, label) {
                try {
                    return fn().catch(function(e) {
                        console.log('[mediasave] ' + label + ' failed: ' + e);
                        return null;
                    });
                } catch (e) {
                    console.log('[mediasave] ' + label + ' threw: ' + e);
                    return Promise.resolve(null);
                }
            }

            attempt(viaGraphql, 'graphql')
                .then(function(item) { return item || attempt(viaMediaInfo, 'media info'); })
                .then(function(item) {
                    if (item) done('ig_item', JSON.stringify(item)); else done('ig_fail');
                })
                .catch(function() { done('ig_fail'); });
        })();
    """.trimIndent()

    private val appIdFinderJs = """
        (function() {
            var result = null;
            document.querySelectorAll('script[type="application/json"]').forEach(function(s) {
                if (result) return;
                var m = (s.textContent || '').match(/"APP_ID":"([0-9]+)"/);
                if (m) result = m[1];
            });
            if (result) AndroidBridge.foundAppId(result);
        })();
    """.trimIndent()

    private val mediaFinderJs = """
        (function() {
            document.querySelectorAll('video').forEach(function(v) {
                if (v.src && !v.src.startsWith('blob:'))
                    AndroidBridge.foundMedia('video', v.src);
                v.querySelectorAll('source').forEach(function(s) {
                    if (s.src && !s.src.startsWith('blob:'))
                        AndroidBridge.foundMedia('video', s.src);
                });
            });
            document.querySelectorAll('img').forEach(function(img) {
                if (!img.alt || img.alt.length === 0) return;
                var src = img.srcset
                    ? img.srcset.split(',').pop().trim().split(' ')[0]
                    : img.src;
                if (src && !src.startsWith('data:') &&
                    (src.includes('cdninstagram') || src.includes('fbcdn')))
                    AndroidBridge.foundMedia('image', src);
            });
        })();
    """.trimIndent()

    private val postMediaFinderJs = """
        (function() {
            var found = [];
            function add(type, url) {
                if (!url || typeof url !== 'string') return;
                if (url.indexOf('cdninstagram.com') < 0 && url.indexOf('fbcdn.net') < 0) return;
                if (found.indexOf(url) >= 0) return;
                found.push(url);
                AndroidBridge.foundMedia(type, url);
            }
            function scan(obj, depth) {
                if (!obj || typeof obj !== 'object' || depth > 12) return;
                if (typeof obj.video_url === 'string') add('video', obj.video_url);
                if (Array.isArray(obj.video_versions) && obj.video_versions[0])
                    add('video', obj.video_versions[0].url || '');
                if (typeof obj.display_url === 'string') add('image', obj.display_url);
                if (obj.image_versions2 && Array.isArray(obj.image_versions2.candidates) && obj.image_versions2.candidates[0])
                    add('image', obj.image_versions2.candidates[0].url || '');
                ['items','carousel_media','edges','shortcode_media','data','node',
                 'edge_sidecar_to_children','xdt_api__v1__media__shortcode__web_info'].forEach(function(k) {
                    if (!obj[k]) return;
                    if (Array.isArray(obj[k])) obj[k].forEach(function(v) { scan(v, depth+1); });
                    else scan(obj[k], depth+1);
                });
            }
            try {
                if (window.__additionalDataLoaded)
                    Object.values(window.__additionalDataLoaded).forEach(function(v) { scan(v, 0); });
            } catch(e) {}
            document.querySelectorAll('script[type="application/json"]').forEach(function(s) {
                try { scan(JSON.parse(s.textContent || '{}'), 0); } catch(e) {}
            });
        })();
    """.trimIndent()
}
