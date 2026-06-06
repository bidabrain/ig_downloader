package com.instadownloader

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var loadBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val capturedMedia = LinkedHashMap<String, MediaItem>()
    private var pendingUrl: String? = null
    private var autoLoadRunnable: Runnable? = null
    // Prevents onPageFinished firing multiple times from triggering multiple downloads
    private var downloadTriggered = false
    // Instagram App ID extracted from page JSON — needed for X-IG-App-ID header
    private var appId = "936619743392459"
    // Suppresses TextWatcher auto-load during Activity state restoration on app open.
    // Set to true in onResume() — which fires AFTER onRestoreInstanceState().
    private var readyForAutoLoad = false
    // True only when the user explicitly loaded a URL via loadInstagram().
    // Auto-download and CDN capture are gated on this so nothing runs without user action.
    private var activeDownloadSession = false
    // Set to true only when we actually see a login page; prevents isAfterLogin false positives
    // when the WebView navigates to the Instagram home page for other reasons.
    private var justLoggedIn = false

    data class MediaItem(val type: String, val url: String, val filename: String)

    companion object {
        // WebView UA — what Instagram sees when browsing
        const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.6099.144 Mobile Safari/537.36"
        // API UA — Instagram mobile app UA; servers accept private API calls from this UA
        const val IG_API_UA = "Mozilla/5.0 (Linux; Android 10; Pixel 7 XL)" +
            "Build/RP1A.20845.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/5.0 Chrome/117.0.5938.60 Mobile Safari/537.36 " +
            "Instagram 307.0.0.34.111"
        val EXTRA_HEADERS = mapOf(
            "X-Requested-With" to "",
            "Accept-Language"  to "en-US,en;q=0.9",
            "Accept"           to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )

        /** Extract the post/reel shortcode from a URL, or null if not a post URL */
        fun shortcodeFrom(url: String): String? {
            val regex = Regex("instagram\\.com/(?:p|reel|reels)/([A-Za-z0-9_-]+)")
            return regex.find(url)?.groupValues?.get(1)
        }
    }

    inner class WebBridge {
        @JavascriptInterface
        fun foundMedia(type: String, url: String) {
            if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
            // Never add media unless the user explicitly triggered this session
            if (!activeDownloadSession) return
            runOnUiThread {
                capturedMedia[url] = MediaItem(type, url, buildFilename(url, type))
                updateDownloadButton()
            }
        }

        @JavascriptInterface
        fun foundAppId(id: String) {
            if (id.isNotBlank()) appId = id
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView     = findViewById(R.id.webView)
        urlInput    = findViewById(R.id.urlInput)
        loadBtn     = findViewById(R.id.loadBtn)
        downloadBtn = findViewById(R.id.downloadBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText  = findViewById(R.id.statusText)

        setupWebView()

        loadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadInstagram(url)
            else Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
        }

        // Auto-load 600 ms after pasting a valid Instagram URL
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Ignore text changes caused by system state restoration on app open
                if (!readyForAutoLoad) return
                val text = s?.toString()?.trim() ?: return
                autoLoadRunnable?.let { urlInput.removeCallbacks(it) }
                if (text.contains("instagram.com")) {
                    val r = Runnable { loadInstagram(text) }
                    autoLoadRunnable = r
                    urlInput.postDelayed(r, 600)
                }
            }
        })

        downloadBtn.setOnClickListener { downloadAll() }

        // Only process the launch intent on a truly fresh start.
        // Guards against two stale-intent cases:
        //   1. Activity recreated after kill: savedInstanceState is non-null.
        //   2. App reopened from Recent Apps history: FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY is set.
        //      Android re-delivers the original share intent in this case, which would
        //      re-trigger the last download session.
        if (savedInstanceState == null) {
            val fromHistory = (intent?.flags ?: 0) and
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            if (!fromHistory) {
                intent?.let { handleIntent(it) }
            }
        }
        // readyForAutoLoad is set in onResume(), which fires AFTER onRestoreInstanceState().
    }

    override fun onResume() {
        super.onResume()
        // onRestoreInstanceState() fires before onResume(), so the EditText text has
        // already been restored by the time we enable auto-load here.
        readyForAutoLoad = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        val url = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.toString() ?: return
        if (url.contains("instagram.com")) {
            urlInput.setText(url)
            loadInstagram(url)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled    = true
            domStorageEnabled    = true
            databaseEnabled      = true
            setSupportZoom(true)
            builtInZoomControls  = true
            displayZoomControls  = false
            loadWithOverviewMode = true
            useWideViewPort      = true
            mixedContentMode     = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString      = UA
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(WebBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                statusText.text = "Loading…"
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()
                // Always try to harvest the App ID Instagram embeds in its page JSON
                injectAppIdFinder()

                when {
                    isLoginPage(url) -> {
                        // Record that we actually went through the login page, so that
                        // the next Instagram home-page load is treated as post-login.
                        justLoggedIn = true
                        statusText.text = "Log in to Instagram — your session is saved automatically"
                    }
                    isAfterLogin(url) -> {
                        justLoggedIn = false   // consume the flag — only fire once
                        statusText.text = "Logged in! Loading your link…"
                        pendingUrl?.let { pu ->
                            downloadTriggered = false
                            webView.postDelayed({ webView.loadUrl(pu, EXTRA_HEADERS) }, 500)
                        }
                    }
                    else -> {
                        // Guard: only act if the user explicitly loaded a URL
                        if (pendingUrl == null) return
                        // Guard: only act once per URL load — Instagram's SPA fires
                        // onPageFinished multiple times as it re-renders
                        if (downloadTriggered) return
                        downloadTriggered = true

                        val shortcode = shortcodeFrom(url)
                        if (shortcode != null) {
                            statusText.text = "Fetching media for post $shortcode…"
                            fetchPostMediaFromApi(shortcode)
                        } else {
                            // Stories, profile etc — scan DOM but let user press button
                            // (don't auto-download: we don't know which images are relevant)
                            statusText.text = "Page loaded — press Download All when ready"
                            injectMediaFinder()
                            webView.postDelayed({ injectMediaFinder() }, 1200)
                        }
                    }
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse
            ) {
                if (request.isForMainFrame) {
                    statusText.text = "HTTP ${errorResponse.statusCode} — tap Load to retry"
                    // Do NOT auto-retry: retrying via view.loadUrl() bypasses loadInstagram(),
                    // which means activeDownloadSession stays false and the session state is
                    // inconsistent. Let the user tap the Load button to retry explicitly.
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                if (request.isForMainFrame) {
                    statusText.text = "Error: ${error.description}"
                }
            }

            // Only used for non-post pages (stories etc.) — not active for /p/ URLs
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                // Never capture anything unless the user explicitly started a session.
                // This prevents profile pictures and feed images from being captured when
                // the WebView restores its state on app open.
                if (!activeDownloadSession) return null
                // Only capture CDN media when we are NOT on a post/reel page
                // (for post pages the API gives us exact URLs)
                val currentUrl = pendingUrl ?: ""
                if (shortcodeFrom(currentUrl) == null) {
                    captureFromUrl(request.url.toString())
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }
    }

    // ── API fetch ──────────────────────────────────────────────────────────────

    /**
     * Call Instagram's GraphQL API to get ONLY the media belonging to [shortcode].
     * Runs on a background thread; posts results back to the UI thread.
     */
    private fun fetchPostMediaFromApi(shortcode: String) {
        Thread {
            val cookie = CookieManager.getInstance().getCookie("https://www.instagram.com") ?: ""
            val media  = tryGraphQlFetch(shortcode, cookie)

            runOnUiThread {
                if (media.isNotEmpty()) {
                    capturedMedia.clear()
                    media.forEach { capturedMedia[it.url] = it }
                    updateDownloadButton()
                    statusText.text = "Found ${media.size} item(s) — press Download All to save"
                } else {
                    // API blocked — extract from the page's own embedded JSON instead.
                    // Do NOT auto-download here: page scan may pick up unrelated images.
                    statusText.text = "Scanning page data… tap Download when count appears"
                    injectPostMediaFinder()
                    webView.postDelayed({ injectPostMediaFinder() }, 2000)
                    webView.postDelayed({ injectPostMediaFinder() }, 4000)
                }
            }
        }.start()
    }

    private fun tryGraphQlFetch(shortcode: String, cookie: String): List<MediaItem> {
        // Attempt 1: query_hash endpoint
        val url1 = "https://www.instagram.com/graphql/query/" +
            "?query_hash=2c4c2e343a8f64c625ba02b2aa12c7f8" +
            "&variables=%7B%22shortcode%22%3A%22$shortcode%22%7D"
        httpGet(url1, cookie, "https://www.instagram.com/p/$shortcode/")?.let {
            val parsed = parseQueryHashResponse(it, shortcode)
            if (parsed.isNotEmpty()) return parsed
        }

        // Attempt 2: query_id endpoint (with relay provider variables, same as IG Helper)
        val url2 = "https://www.instagram.com/graphql/query/" +
            "?query_id=9496392173716084" +
            "&variables=%7B%22shortcode%22%3A%22$shortcode%22" +
            "%2C%22__relay_internal__pv__PolarisFeedShareMenurelayprovider%22%3Atrue" +
            "%2C%22__relay_internal__pv__PolarisIsLoggedInrelayprovider%22%3Atrue%7D"
        httpGet(url2, cookie, "https://www.instagram.com/p/$shortcode/")?.let {
            val parsed = parseQueryIdResponse(it, shortcode)
            if (parsed.isNotEmpty()) return parsed
        }

        // Attempt 3: v1 web_info endpoint
        val url3 = "https://www.instagram.com/api/v1/media/shortcode/web_info/?shortcode=$shortcode"
        httpGet(url3, cookie, "https://www.instagram.com/p/$shortcode/")?.let {
            val parsed = parseQueryIdResponse(it, shortcode)
            if (parsed.isNotEmpty()) return parsed
        }

        return emptyList()
    }

    private fun httpGet(url: String, cookie: String, referer: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000
            // Use Instagram mobile app UA — same trick as the IG Helper userscript
            conn.setRequestProperty("User-Agent",      IG_API_UA)
            conn.setRequestProperty("Cookie",          cookie)
            conn.setRequestProperty("Referer",         referer)
            conn.setRequestProperty("X-Requested-With","")
            conn.setRequestProperty("Accept",          "application/json, */*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            // App ID extracted from Instagram's own page — required for query_id endpoint
            conn.setRequestProperty("X-IG-App-ID",     appId)
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
            else null
        } catch (e: Exception) { null }
    }

    private fun parseQueryHashResponse(json: String, shortcode: String): List<MediaItem> {
        return try {
            val root     = JSONObject(json)
            val resource = root.getJSONObject("data").getJSONObject("shortcode_media")
            val username = resource.optJSONObject("owner")?.optString("username") ?: "instagram"
            mediaItemsFromResource(resource, username, shortcode)
        } catch (e: Exception) { emptyList() }
    }

    private fun parseQueryIdResponse(json: String, shortcode: String): List<MediaItem> {
        return try {
            val root     = JSONObject(json)
            val items    = root.getJSONObject("data")
                .getJSONObject("xdt_api__v1__media__shortcode__web_info")
                .getJSONArray("items")
            if (items.length() == 0) return emptyList()
            val item     = items.getJSONObject(0)
            val username = item.optJSONObject("user")?.optString("username") ?: "instagram"
            mediaItemsFromV1Item(item, username, shortcode)
        } catch (e: Exception) { emptyList() }
    }

    /** Parse a shortcode_media object (query_hash style) */
    private fun mediaItemsFromResource(
        resource: JSONObject,
        username: String,
        shortcode: String
    ): List<MediaItem> {
        val results  = mutableListOf<MediaItem>()
        val typename = resource.optString("__typename", "")

        fun bestImage(res: JSONObject): String {
            val arr = res.optJSONArray("display_resources")
            return if (arr != null && arr.length() > 0)
                arr.getJSONObject(arr.length() - 1).optString("src")
            else res.optString("display_url")
        }

        when (typename) {
            "GraphVideo"   -> {
                val url = resource.optString("video_url")
                if (url.isNotEmpty()) results += MediaItem("video", url, "${username}_$shortcode.mp4")
            }
            "GraphImage"   -> {
                val url = bestImage(resource)
                if (url.isNotEmpty()) results += MediaItem("image", url, "${username}_$shortcode.jpg")
            }
            "GraphSidecar" -> {
                val edges = resource.optJSONObject("edge_sidecar_to_children")
                    ?.optJSONArray("edges") ?: return results
                for (i in 0 until edges.length()) {
                    val node = edges.getJSONObject(i).optJSONObject("node") ?: continue
                    val idx  = i + 1
                    if (node.optString("__typename") == "GraphVideo") {
                        val url = node.optString("video_url")
                        if (url.isNotEmpty()) results += MediaItem("video", url, "${username}_${shortcode}_$idx.mp4")
                    } else {
                        val url = bestImage(node)
                        if (url.isNotEmpty()) results += MediaItem("image", url, "${username}_${shortcode}_$idx.jpg")
                    }
                }
            }
        }
        return results
    }

    /** Parse a v1/media item object (query_id style) */
    private fun mediaItemsFromV1Item(
        item: JSONObject,
        username: String,
        shortcode: String
    ): List<MediaItem> {
        val results = mutableListOf<MediaItem>()

        fun bestV1Image(node: JSONObject): String {
            val candidates = node.optJSONObject("image_versions2")?.optJSONArray("candidates")
            return if (candidates != null && candidates.length() > 0)
                candidates.getJSONObject(0).optString("url")
            else ""
        }

        val carouselMedia = item.optJSONArray("carousel_media")
        if (carouselMedia != null) {
            for (i in 0 until carouselMedia.length()) {
                val node = carouselMedia.getJSONObject(i)
                val idx  = i + 1
                val videos = node.optJSONArray("video_versions")
                if (videos != null && videos.length() > 0) {
                    val url = videos.getJSONObject(0).optString("url")
                    if (url.isNotEmpty()) results += MediaItem("video", url, "${username}_${shortcode}_$idx.mp4")
                } else {
                    val url = bestV1Image(node)
                    if (url.isNotEmpty()) results += MediaItem("image", url, "${username}_${shortcode}_$idx.jpg")
                }
            }
        } else {
            val videos = item.optJSONArray("video_versions")
            if (videos != null && videos.length() > 0) {
                val url = videos.getJSONObject(0).optString("url")
                if (url.isNotEmpty()) results += MediaItem("video", url, "${username}_$shortcode.mp4")
            } else {
                val url = bestV1Image(item)
                if (url.isNotEmpty()) results += MediaItem("image", url, "${username}_$shortcode.jpg")
            }
        }
        return results
    }

    // ── App ID extractor ──────────────────────────────────────────────────────

    /** Read Instagram's own APP_ID from embedded page JSON (same as IG Helper's getAppID()) */
    private fun injectAppIdFinder() {
        val js = """
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
        webView.evaluateJavascript(js, null)
    }

    // ── Page scan (fallback / non-post pages) ──────────────────────────────────

    private fun captureFromUrl(url: String) {
        if (url.isBlank()) return
        val isIgCdn = url.contains("cdninstagram.com") || url.contains("fbcdn.net")
        if (!isIgCdn) return
        val isVideo = url.contains(".mp4") && !url.contains("thumbnail")
        val isImage = !isVideo &&
            (url.contains(".jpg") || url.contains(".webp")) &&
            (url.contains("_e35") || url.contains("_e15") ||
             url.contains("1080x"))
        // Note: _n.jpg was removed — that suffix also matches profile picture URLs
        val type = when { isVideo -> "video"; isImage -> "image"; else -> return }
        runOnUiThread {
            capturedMedia[url] = MediaItem(type, url, buildFilename(url, type))
            updateDownloadButton()
        }
    }

    private fun injectMediaFinder() {
        val js = """
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
        webView.evaluateJavascript(js, null)
    }

    /**
     * Fallback for when the API is blocked: parse the post's own embedded JSON
     * (same data Instagram's React app already has) rather than scanning all img tags.
     */
    private fun injectPostMediaFinder() {
        val js = """
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
                try { if (window.__additionalDataLoaded) Object.values(window.__additionalDataLoaded).forEach(function(v){scan(v,0);}); } catch(e){}
                document.querySelectorAll('script[type="application/json"]').forEach(function(s){
                    try { scan(JSON.parse(s.textContent||'{}'), 0); } catch(e){}
                });
                return found.length;
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun isLoginPage(url: String) =
        url.contains("accounts/login") || url.contains("/login") ||
        url.contains("challenge") || url.contains("accounts/suspended")

    private fun isAfterLogin(url: String): Boolean {
        // Only treat the Instagram home page as post-login if we actually saw the login
        // page first (justLoggedIn == true). Without this guard, navigating to the home
        // page for any other reason (WebView state restore, tapping the Instagram logo, etc.)
        // would wrongly re-trigger the entire download chain.
        if (!justLoggedIn) return false
        val trimmed = url.trimEnd('/')
        return (trimmed == "https://www.instagram.com" || trimmed == "https://instagram.com") &&
               pendingUrl != null && pendingUrl != trimmed
    }

    private fun loadInstagram(url: String) {
        var finalUrl = url
        if (!url.startsWith("http://") && !url.startsWith("https://"))
            finalUrl = "https://$url"
        pendingUrl = finalUrl
        downloadTriggered = false
        activeDownloadSession = true   // user explicitly requested this load
        capturedMedia.clear()
        updateDownloadButton()
        webView.loadUrl(finalUrl, EXTRA_HEADERS)
    }

    private fun updateDownloadButton() {
        val n = capturedMedia.size
        downloadBtn.text = if (n > 0) "Download All ($n found)" else "Download All"
    }

    private fun downloadAll() {
        if (capturedMedia.isEmpty()) {
            statusText.text = "No media found — make sure you are logged in"
            return
        }
        val count = capturedMedia.size
        capturedMedia.values.forEach { enqueueDownload(it) }
        statusText.text = "Downloading $count item(s) — check notification bar"
        Toast.makeText(this, "Downloading $count item(s)", Toast.LENGTH_SHORT).show()

        // Clear session so that closing and reopening the app never re-triggers downloads.
        // The DownloadManager runs as a system service and will finish independently.
        activeDownloadSession = false
        downloadTriggered = true
        pendingUrl = null
        capturedMedia.clear()
        updateDownloadButton()
    }

    private fun enqueueDownload(item: MediaItem) {
        try {
            val cookie = CookieManager.getInstance().getCookie(item.url)
            val req = DownloadManager.Request(Uri.parse(item.url)).apply {
                setTitle(item.filename)
                setDescription("Insta Downloader")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Instagram/${item.filename}")
                addRequestHeader("User-Agent", UA)
                addRequestHeader("Referer", "https://www.instagram.com/")
                if (!cookie.isNullOrBlank()) addRequestHeader("Cookie", cookie)
            }
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildFilename(url: String, type: String): String {
        return try {
            Uri.parse(url).lastPathSegment?.substringBefore("?")?.takeIf { it.isNotBlank() }
                ?: "instagram_${System.currentTimeMillis()}.${if (type == "video") "mp4" else "jpg"}"
        } catch (_: Exception) {
            "instagram_${System.currentTimeMillis()}.${if (type == "video") "mp4" else "jpg"}"
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
