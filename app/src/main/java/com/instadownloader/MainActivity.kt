package com.instadownloader

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var loadBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var browserBtn: Button
    private lateinit var loginBtn: Button
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
    // True only when the user explicitly loaded a URL via loadUrl().
    // Auto-download and CDN capture are gated on this so nothing runs without user action.
    private var activeDownloadSession = false
    // Set to true only when we actually see a login page; prevents isAfterLogin false positives
    // when the WebView navigates to the Instagram home page for other reasons.
    private var justLoggedIn = false
    // Which platform the current session belongs to: "instagram" or "rednote"
    private var currentPlatform = "instagram"
    // Track the last URL we ran media extraction on, so redirect chains (e.g. xhslink.com →
    // xiaohongshu.com) don't get blocked by the downloadTriggered guard on the final URL.
    private var lastExtractedUrl: String? = null

    data class MediaItem(val type: String, val url: String, val filename: String, val source: String = "instagram")

    companion object {
        // WebView UA — what sites see when browsing
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

        const val REQ_WRITE_STORAGE = 1001

        /** Extract the post/reel shortcode from an Instagram URL, or null if not a post URL */
        fun shortcodeFrom(url: String): String? {
            val regex = Regex("instagram\\.com/(?:p|reel|reels)/([A-Za-z0-9_-]+)")
            return regex.find(url)?.groupValues?.get(1)
        }

        /** Return true if the URL belongs to RedNote (小红书), including short links */
        fun isRedNoteUrl(url: String) =
            url.contains("xiaohongshu.com") || url.contains("rednote.com") ||
            url.contains("xhslink.com")

        /** Return true if the URL belongs to X / Twitter */
        fun isXUrl(url: String) = url.contains("x.com") || url.contains("twitter.com")

        /** Extract the numeric tweet/status ID from an X or Twitter URL, or null */
        fun tweetIdFrom(url: String): String? {
            val regex = Regex("(?:twitter\\.com|x\\.com)/[^/]+/status/(\\d+)")
            return regex.find(url)?.groupValues?.get(1)
        }

        // X/Twitter GraphQL constants
        // Bearer token is the public guest token embedded in all X web clients
        const val X_BEARER = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val X_QUERY_ID = "zAz9764BcLZOJ0JU2wrd1A"
    }

    inner class WebBridge {
        @JavascriptInterface
        fun foundMedia(type: String, url: String) {
            if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
            // Never add media unless the user explicitly triggered this session
            if (!activeDownloadSession) return
            runOnUiThread {
                capturedMedia[url] = MediaItem(type, url, buildFilename(url, type), currentPlatform)
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
        browserBtn  = findViewById(R.id.browserBtn)
        loginBtn    = findViewById(R.id.loginBtn)
        progressBar = findViewById(R.id.progressBar)
        statusText  = findViewById(R.id.statusText)

        // Always start with a clean input — never restore old URL from any saved state
        urlInput.setText("")

        setupWebView()

        loadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadUrl(url)
            else Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
        }

        // Auto-load 600 ms after pasting a valid URL
        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // Ignore text changes caused by system state restoration on app open
                if (!readyForAutoLoad) return
                val text = s?.toString()?.trim() ?: return
                autoLoadRunnable?.let { urlInput.removeCallbacks(it) }
                if (text.contains("instagram.com") || isRedNoteUrl(text) || isXUrl(text)) {
                    val r = Runnable { loadUrl(text) }
                    autoLoadRunnable = r
                    urlInput.postDelayed(r, 600)
                }
            }
        })

        downloadBtn.setOnClickListener { downloadAll() }

        browserBtn.setOnClickListener {
            val visible = webView.visibility == View.VISIBLE
            webView.visibility = if (visible) View.GONE else View.VISIBLE
            browserBtn.text    = if (visible) "Browser" else "Hide Browser"
        }

        // Navigate the in-app WebView to the platform's login page.
        // We load it with EXTRA_HEADERS (which blanks X-Requested-With) and
        // shouldOverrideUrlLoading ensures every subsequent click/redirect also
        // carries those headers — so Instagram/X never see the WebView fingerprint.
        loginBtn.setOnClickListener {
            val loginUrl = when (currentPlatform) {
                "twitter"  -> "https://x.com/i/flow/login"
                "rednote"  -> "https://www.xiaohongshu.com/"
                else       -> "https://www.instagram.com/accounts/login/"
            }
            webView.visibility = View.VISIBLE
            browserBtn.text    = "Hide Browser"
            webView.loadUrl(loginUrl, EXTRA_HEADERS)
        }

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
        if (url.contains("instagram.com") || isRedNoteUrl(url) || isXUrl(url)) {
            urlInput.setText(url)
            loadUrl(url)
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

            // Re-load every GET navigation through loadUrl() so our EXTRA_HEADERS are
            // applied (blanking X-Requested-With). Without this, Android injects
            // "X-Requested-With: com.instadownloader" on every subsequent request,
            // which Instagram and X use to detect — and block — WebView logins.
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: ""
                // Block non-http(s) schemes (e.g. xhslink://, snssdk://, intent://)
                // to prevent ERR_UNKNOWN_URL_SCHEME from RedNote's in-page app links.
                if (scheme != "http" && scheme != "https") return true
                if (request.method?.uppercase() == "GET") {
                    view.loadUrl(request.url.toString(), EXTRA_HEADERS)
                    return true
                }
                return false   // let POST (form submit) go through unchanged
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = 0
                statusText.text = "Loading…"
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()

                // Instagram: always harvest App ID from page JSON
                if (currentPlatform == "instagram") injectAppIdFinder()

                when {
                    isLoginPage(url) -> {
                        justLoggedIn = true
                        statusText.text = "Log in — tap Browser to open the login page"
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
                        // Guard: skip if we already processed this exact URL.
                        // Using URL equality (not a boolean flag) lets redirect chains work:
                        // xhslink.com fires onPageFinished (processed, finds nothing), then
                        // xiaohongshu.com fires (different URL → processed again, finds media).
                        // The boolean downloadTriggered still prevents double-processing of the
                        // same URL from SPA re-renders.
                        if (downloadTriggered && url == lastExtractedUrl) return
                        downloadTriggered = true
                        lastExtractedUrl = url

                        when (currentPlatform) {
                        "rednote" -> {
                            statusText.text = "Extracting RedNote media…"
                            // Retry a few times — __INITIAL_STATE__ may not be ready immediately
                            injectRedNoteMediaFinder()
                            webView.postDelayed({ injectRedNoteMediaFinder() }, 1500)
                            webView.postDelayed({ injectRedNoteMediaFinder() }, 3500)
                        }
                        "twitter" -> {
                            val tweetId = tweetIdFrom(url)
                            if (tweetId != null) {
                                statusText.text = "Fetching media for tweet $tweetId…"
                                fetchTwitterMediaFromApi(tweetId)
                            } else {
                                statusText.text = "Page loaded — press Download All when ready"
                            }
                        }
                        else -> {
                            val shortcode = shortcodeFrom(url)
                            if (shortcode != null) {
                                statusText.text = "Fetching media for post $shortcode…"
                                fetchPostMediaFromApi(shortcode)
                            } else {
                                // Stories, profile etc — scan DOM but let user press button
                                statusText.text = "Page loaded — press Download All when ready"
                                injectMediaFinder()
                                webView.postDelayed({ injectMediaFinder() }, 1200)
                            }
                        }
                        } // end when(currentPlatform)
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

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                if (!activeDownloadSession) return null
                when (currentPlatform) {
                    "rednote" -> captureRedNoteMedia(request.url.toString())
                    "instagram" -> {
                        // CDN interception for non-post pages (stories etc.)
                        val currentUrl = pendingUrl ?: ""
                        if (shortcodeFrom(currentUrl) == null) {
                            captureFromUrl(request.url.toString())
                        }
                    }
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

    // ── Instagram API fetch ────────────────────────────────────────────────────

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
            conn.setRequestProperty("User-Agent",      IG_API_UA)
            conn.setRequestProperty("Cookie",          cookie)
            conn.setRequestProperty("Referer",         referer)
            conn.setRequestProperty("X-Requested-With","")
            conn.setRequestProperty("Accept",          "application/json, */*")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
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

    // ── X / Twitter API fetch ─────────────────────────────────────────────────

    private fun fetchTwitterMediaFromApi(tweetId: String) {
        Thread {
            val cookie = CookieManager.getInstance().getCookie("https://x.com") ?: ""
            val ct0    = extractCt0(cookie)

            // 1. Try with session cookies (works for private accounts if user is logged in)
            var media = if (ct0.isNotEmpty()) tryFetchTwitterMedia(tweetId, cookie, ct0, null)
                        else emptyList()

            // 2. Fall back to guest token — works for ALL public tweets, no login needed
            if (media.isEmpty()) {
                val guestToken = fetchXGuestToken()
                if (guestToken != null) {
                    media = tryFetchTwitterMedia(tweetId, "", "", guestToken)
                }
            }

            runOnUiThread {
                if (media.isNotEmpty()) {
                    capturedMedia.clear()
                    media.forEach { capturedMedia[it.url] = it }
                    updateDownloadButton()
                    statusText.text = "Found ${media.size} item(s) — press Download All to save"
                } else {
                    statusText.text = "No media found. Private account? Tap Login, log in inside the Browser, then reload."
                }
            }
        }.start()
    }

    /**
     * Obtain a short-lived guest token from X's public endpoint.
     * Guest tokens let us call the GraphQL API for any public tweet without any login.
     */
    private fun fetchXGuestToken(): String? {
        return try {
            val conn = URL("https://api.twitter.com/1.1/guest/activate.json")
                .openConnection() as HttpURLConnection
            conn.requestMethod   = "POST"
            conn.connectTimeout  = 10_000
            conn.readTimeout     = 10_000
            conn.doOutput        = true
            conn.setRequestProperty("Authorization",   X_BEARER)
            conn.setRequestProperty("Content-Length",  "0")
            conn.setRequestProperty("User-Agent",      UA)
            conn.outputStream.close()   // send empty body
            if (conn.responseCode == 200)
                JSONObject(conn.inputStream.bufferedReader().readText())
                    .optString("guest_token").takeIf { it.isNotEmpty() }
            else null
        } catch (e: Exception) { null }
    }

    private fun extractCt0(cookie: String): String =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("ct0=") }
            ?.substringAfter("ct0=") ?: ""

    /**
     * Call the TweetResultByRestId GraphQL endpoint.
     * Pass [guestToken] for unauthenticated public access, or [ct0] + [cookie] for
     * authenticated access (needed for private accounts).
     */
    private fun tryFetchTwitterMedia(
        tweetId: String,
        cookie: String,
        ct0: String,
        guestToken: String?
    ): List<MediaItem> {
        val variables    = """{"tweetId":"$tweetId","withCommunity":false,"includePromotedContent":false,"withVoice":false}"""
        val features     = """{"creator_subscriptions_tweet_preview_api_enabled":true,"premium_content_api_read_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"responsive_web_grok_analyze_button_fetch_trends_enabled":false,"responsive_web_grok_analyze_post_followups_enabled":false,"responsive_web_jetfuel_frame":false,"responsive_web_grok_share_attachment_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"responsive_web_grok_show_grok_translated_post":false,"responsive_web_grok_analysis_button_from_backend":false,"creator_subscriptions_quote_tweet_preview_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"profile_label_improvements_pcf_label_in_post_enabled":true,"rweb_tipjar_consumption_enabled":true,"verified_phone_label_enabled":false,"responsive_web_grok_image_annotation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_enhance_cards_enabled":false}"""
        val fieldToggles = """{"withArticleRichContentState":true,"withArticlePlainText":false,"withGrokAnalyze":false,"withDisallowedReplyControls":false}"""

        val enc = "UTF-8"
        val url = "https://x.com/i/api/graphql/$X_QUERY_ID/TweetResultByRestId" +
            "?variables=${URLEncoder.encode(variables, enc)}" +
            "&features=${URLEncoder.encode(features, enc)}" +
            "&fieldToggles=${URLEncoder.encode(fieldToggles, enc)}"

        val json = httpGetTwitter(url, cookie, ct0, guestToken) ?: return emptyList()
        return parseTwitterResponse(json, tweetId)
    }

    private fun httpGetTwitter(
        url: String,
        cookie: String,
        ct0: String,
        guestToken: String?
    ): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12_000
            conn.readTimeout    = 12_000
            conn.setRequestProperty("User-Agent",                UA)
            conn.setRequestProperty("authorization",             X_BEARER)
            conn.setRequestProperty("x-twitter-active-user",    "yes")
            conn.setRequestProperty("x-twitter-client-language","en")
            conn.setRequestProperty("content-type",             "application/json")
            conn.setRequestProperty("Referer",                  "https://x.com/")
            if (guestToken != null) {
                // Unauthenticated guest mode — no CSRF token needed
                conn.setRequestProperty("x-guest-token", guestToken)
            } else {
                // Authenticated session mode
                conn.setRequestProperty("x-csrf-token",         ct0)
                conn.setRequestProperty("x-twitter-auth-type",  "OAuth2Session")
                conn.setRequestProperty("Cookie",               cookie)
            }
            if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText()
            else null
        } catch (e: Exception) { null }
    }

    private fun parseTwitterResponse(json: String, tweetId: String): List<MediaItem> {
        return try {
            val root   = JSONObject(json)
            val result = root.getJSONObject("data").getJSONObject("tweetResult").getJSONObject("result")
            // Response wraps the tweet in a "tweet" key for some visibility result types
            val tweet  = if (result.has("tweet")) result.getJSONObject("tweet") else result

            val username = try {
                tweet.getJSONObject("core")
                    .getJSONObject("user_results").getJSONObject("result")
                    .getJSONObject("legacy").getString("screen_name")
            } catch (_: Exception) { "twitter" }

            val legacy      = tweet.optJSONObject("legacy") ?: return emptyList()
            val extEntities = legacy.optJSONObject("extended_entities") ?: return emptyList()
            val mediaArray  = extEntities.optJSONArray("media") ?: return emptyList()

            val results = mutableListOf<MediaItem>()
            for (i in 0 until mediaArray.length()) {
                val media = mediaArray.getJSONObject(i)
                val idx   = i + 1
                when (media.optString("type")) {
                    "photo" -> {
                        val base = media.optString("media_url_https")
                        if (base.isNotEmpty())
                            results += MediaItem("image", "$base?format=jpg&name=4096x4096",
                                "${username}_${tweetId}_img$idx.jpg", "twitter")
                    }
                    "video", "animated_gif" -> {
                        val variants = media.optJSONObject("video_info")
                            ?.optJSONArray("variants") ?: continue
                        var bestUrl     = ""
                        var bestBitrate = -1
                        for (j in 0 until variants.length()) {
                            val v = variants.getJSONObject(j)
                            if (v.optString("content_type") == "video/mp4") {
                                val br = v.optInt("bitrate", 0)
                                if (br > bestBitrate) { bestBitrate = br; bestUrl = v.optString("url") }
                            }
                        }
                        if (bestUrl.isNotEmpty())
                            results += MediaItem("video", bestUrl,
                                "${username}_${tweetId}_vid$idx.mp4", "twitter")
                    }
                }
            }
            results
        } catch (e: Exception) { emptyList() }
    }

    // ── App ID extractor ──────────────────────────────────────────────────────

    /** Read Instagram's own APP_ID from embedded page JSON */
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

    // ── RedNote media extractor ───────────────────────────────────────────────

    /**
     * Extract media from RedNote's __INITIAL_STATE__, ported from XHS-Downloader.
     *
     * Note object paths (mirroring XHS-Downloader's Converter.py):
     *   Phone: noteData.data.noteData
     *   PC:    note.noteDetailMap[<url-id>].note  (fallback: last entry)
     *
     * Video strategy (mirroring XHS-Downloader's video.py):
     *   1. video.consumer.originVideoKey → direct MP4 on sns-video-bd.xhscdn.com
     *   2. Collect h264 + h265 streams, sort by height, pick highest quality.
     *      Prefer backupUrls[0] (direct MP4) over masterUrl (may be .m3u8 HLS).
     *
     * Image strategy (mirroring XHS-Downloader's image.py):
     *   Extract token via url.split('/').slice(5).join('/').split('!')[0]
     *   and reconstruct as ci.xiaohongshu.com/{token}?imageView2/format/jpeg
     */
    private fun injectRedNoteMediaFinder() {
        val js = """
            (function() {
                var state = window.__INITIAL_STATE__;
                if (!state) return;

                var note = null;

                // Path 1: phone layout — noteData.data.noteData
                try {
                    if (state.noteData && state.noteData.data && state.noteData.data.noteData) {
                        note = state.noteData.data.noteData;
                    }
                } catch(e) {}

                // Path 2: PC layout — note.noteDetailMap[<url-id>].note
                if (!note) {
                    try {
                        var m = location.pathname.match(/\/explore\/([^?\/]+)/);
                        if (!m) m = location.pathname.match(/\/discovery\/item\/([^?\/]+)/);
                        if (m && state.note && state.note.noteDetailMap) {
                            var entry = state.note.noteDetailMap[m[1]];
                            if (entry) note = entry.note || entry;
                        }
                    } catch(e) {}
                }

                // Path 3: PC layout fallback — last entry in noteDetailMap
                if (!note) {
                    try {
                        if (state.note && state.note.noteDetailMap) {
                            var keys = Object.keys(state.note.noteDetailMap);
                            if (keys.length > 0) {
                                var last = state.note.noteDetailMap[keys[keys.length - 1]];
                                if (last) note = last.note || last;
                            }
                        }
                    } catch(e) {}
                }

                if (!note) return;

                if (note.type === 'video') {
                    // Strategy 1: originVideoKey — direct MP4, most reliable
                    try {
                        var key = note.video && note.video.consumer && note.video.consumer.originVideoKey;
                        if (key) {
                            AndroidBridge.foundMedia('video', 'https://sns-video-bd.xhscdn.com/' + key);
                            return;
                        }
                    } catch(e) {}

                    // Strategy 2: collect h264 + h265 streams, pick highest-resolution direct MP4
                    var allStreams = [];
                    try {
                        var h264 = note.video.media.stream.h264;
                        if (Array.isArray(h264)) allStreams = allStreams.concat(h264);
                    } catch(e) {}
                    try {
                        var h265 = note.video.media.stream.h265;
                        if (Array.isArray(h265)) allStreams = allStreams.concat(h265);
                    } catch(e) {}

                    if (allStreams.length > 0) {
                        allStreams.sort(function(a, b) { return (a.height || 0) - (b.height || 0); });
                        var best = allStreams[allStreams.length - 1];
                        // backupUrls[0] is a direct MP4; masterUrl may be .m3u8 HLS
                        var videoUrl = null;
                        if (best.backupUrls && best.backupUrls.length > 0 &&
                                best.backupUrls[0].indexOf('.m3u8') < 0) {
                            videoUrl = best.backupUrls[0];
                        } else if (best.masterUrl && best.masterUrl.indexOf('.m3u8') < 0) {
                            videoUrl = best.masterUrl;
                        }
                        if (videoUrl) AndroidBridge.foundMedia('video', videoUrl);
                    }

                    // Strategy 3: DOM fallback — read <video> element src directly
                    document.querySelectorAll('video').forEach(function(v) {
                        var src = v.currentSrc || v.src || '';
                        if (src && src.indexOf('blob:') < 0 && src.indexOf('data:') < 0 && src.length > 10)
                            AndroidBridge.foundMedia('video', src);
                        v.querySelectorAll('source').forEach(function(s) {
                            if (s.src && s.src.indexOf('blob:') < 0)
                                AndroidBridge.foundMedia('video', s.src);
                        });
                    });
                } else {
                    // Image post
                    // Token extraction mirrors XHS-Downloader image.py:
                    //   "/".join(url.split("/")[5:]).split("!")[0]
                    // Works for any CDN domain and handles multi-segment tokens.
                    var images = note.imageList;
                    if (!images || !images.length) return;
                    images.forEach(function(item) {
                        var url = item.urlDefault || item.url || '';
                        if (!url) return;
                        var token = url.split('/').slice(5).join('/').split('!')[0];
                        if (token) {
                            AndroidBridge.foundMedia('image',
                                'https://ci.xiaohongshu.com/' + token + '?imageView2/format/jpeg');
                        }
                    });
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ── RedNote CDN capture (network interception) ───────────────────────────

    /**
     * Called from shouldInterceptRequest for every RedNote resource request.
     * Captures direct MP4 URLs from RedNote's video CDN (sns-video-*.xhscdn.com).
     * Skips HLS manifests (.m3u8) and TS segments — those are unplayable as saved files.
     */
    private fun captureRedNoteMedia(url: String) {
        if (!activeDownloadSession) return
        // Only video CDN subdomains (sns-video-bd, sns-video-hw, etc.)
        if (!url.contains("xhscdn.com")) return
        if (!url.contains("sns-video")) return
        // Skip HLS manifest and segments
        if (url.contains(".m3u8") || url.contains(".ts?") || url.contains("/ts/")) return
        runOnUiThread {
            if (!capturedMedia.containsKey(url)) {
                capturedMedia[url] = MediaItem("video", url, buildFilename(url, "video"), "rednote")
                updateDownloadButton()
            }
        }
    }

    // ── Instagram page scan (fallback / non-post pages) ───────────────────────

    private fun captureFromUrl(url: String) {
        if (url.isBlank()) return
        val isIgCdn = url.contains("cdninstagram.com") || url.contains("fbcdn.net")
        if (!isIgCdn) return
        val isVideo = url.contains(".mp4") && !url.contains("thumbnail")
        val isImage = !isVideo &&
            (url.contains(".jpg") || url.contains(".webp")) &&
            (url.contains("_e35") || url.contains("_e15") ||
             url.contains("1080x"))
        val type = when { isVideo -> "video"; isImage -> "image"; else -> return }
        runOnUiThread {
            capturedMedia[url] = MediaItem(type, url, buildFilename(url, type), "instagram")
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
        if (!justLoggedIn) return false
        val trimmed = url.trimEnd('/')
        val isIgHome  = trimmed == "https://www.instagram.com" || trimmed == "https://instagram.com"
        val isXHome   = trimmed == "https://x.com/home" || trimmed == "https://twitter.com/home"
        return (isIgHome || isXHome) && pendingUrl != null && pendingUrl != trimmed
    }

    private fun loadUrl(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://"))
            finalUrl = "https://$finalUrl"
        // RedNote shares URLs as http:// — upgrade to HTTPS to avoid ERR_CLEARTEXT_NOT_PERMITTED
        if (finalUrl.startsWith("http://"))
            finalUrl = finalUrl.replaceFirst("http://", "https://")
        currentPlatform = when {
            isRedNoteUrl(finalUrl) -> "rednote"
            isXUrl(finalUrl)       -> "twitter"
            else                   -> "instagram"
        }
        pendingUrl = finalUrl
        downloadTriggered = false
        lastExtractedUrl = null
        activeDownloadSession = true
        capturedMedia.clear()
        updateDownloadButton()
        // Collapse the browser panel on each new load so it doesn't stay open between sessions
        webView.visibility = View.GONE
        browserBtn.text    = "Browser"
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

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_WRITE_STORAGE
            )
            return
        }

        val items = capturedMedia.values.toList()
        val total = items.size
        val folderName = when (items.firstOrNull()?.source) {
            "rednote"  -> "RedNote"
            "twitter"  -> "Twitter"
            else       -> "Instagram"
        }

        // Clear session immediately so reopen never re-triggers
        activeDownloadSession = false
        downloadTriggered = true
        pendingUrl = null
        capturedMedia.clear()
        updateDownloadButton()

        downloadBtn.isEnabled = false
        progressBar.max = 100
        progressBar.progress = 0
        progressBar.visibility = View.VISIBLE

        Thread {
            var succeeded = 0
            for ((index, item) in items.withIndex()) {
                val num = index + 1
                try {
                    downloadFileInApp(item) { pct ->
                        runOnUiThread {
                            progressBar.progress = (index * 100 + pct) / total
                            statusText.text = "下载中 $num/$total ($pct%)"
                        }
                    }
                    succeeded++
                } catch (e: Exception) {
                    runOnUiThread {
                        statusText.text = "第 $num 个失败：${e.message}"
                    }
                }
            }
            runOnUiThread {
                progressBar.visibility = View.GONE
                downloadBtn.isEnabled = true
                statusText.text = if (succeeded == total)
                    "下载完成，共 $total 个文件，保存至 下载/$folderName/"
                else
                    "完成：$succeeded/$total 成功"
            }
        }.start()
    }

    private fun downloadFileInApp(item: MediaItem, onProgress: (Int) -> Unit) {
        val folderName = when (item.source) {
            "rednote"  -> "RedNote"
            "twitter"  -> "Twitter"
            else       -> "Instagram"
        }

        val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout    = 60_000
            setRequestProperty("User-Agent", UA)
            when (item.source) {
                "rednote" -> {
                    // RedNote CDN does not require a Referer; omit to avoid 403s
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
                }
                "twitter" -> {
                    setRequestProperty("Referer", "https://x.com/")
                    val cookie = CookieManager.getInstance().getCookie("https://x.com")
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
                else -> {
                    setRequestProperty("Referer", "https://www.instagram.com/")
                    val cookie = CookieManager.getInstance().getCookie(item.url)
                    if (!cookie.isNullOrBlank()) setRequestProperty("Cookie", cookie)
                }
            }
            connect()
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK)
            throw Exception("HTTP ${conn.responseCode}")

        val totalBytes = conn.contentLengthLong
        val input = conn.inputStream
        val mimeType = if (item.type == "video") "video/mp4" else "image/jpeg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.filename)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/$folderName")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Cannot create file in MediaStore")
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    streamWithProgress(input, out, totalBytes, onProgress)
                }
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) },
                    null, null
                )
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                folderName
            )
            dir.mkdirs()
            val file = File(dir, item.filename)
            FileOutputStream(file).use { out ->
                streamWithProgress(input, out, totalBytes, onProgress)
            }
        }
    }

    private fun streamWithProgress(
        input: InputStream,
        output: OutputStream,
        totalBytes: Long,
        onProgress: (Int) -> Unit
    ) {
        val buf = ByteArray(16 * 1024)
        var downloaded = 0L
        var lastPct = -1
        var len: Int
        while (input.read(buf).also { len = it } != -1) {
            output.write(buf, 0, len)
            downloaded += len
            if (totalBytes > 0) {
                val pct = (downloaded * 100 / totalBytes).toInt()
                if (pct != lastPct) { lastPct = pct; onProgress(pct) }
            }
        }
        if (totalBytes <= 0) onProgress(100)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            statusText.text = "权限已获取，请再次点击下载"
        }
    }

    private fun buildFilename(url: String, type: String): String {
        val ext = if (type == "video") "mp4" else "jpg"
        return try {
            val seg = Uri.parse(url).lastPathSegment?.substringBefore("?")?.takeIf { it.isNotBlank() }
                ?: return "media_${System.currentTimeMillis()}.$ext"
            // Instagram segments already contain an extension (e.g. abc_e35.jpg);
            // RedNote image IDs do not, so append one.
            if ('.' in seg) seg else "$seg.$ext"
        } catch (_: Exception) {
            "media_${System.currentTimeMillis()}.$ext"
        }
    }

    private var backPressedTime = 0L

    override fun onBackPressed() {
        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            urlInput.setText("")
            pendingUrl = null
            activeDownloadSession = false
            capturedMedia.clear()
            super.onBackPressed()
        } else {
            backPressedTime = now
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
        }
    }
}
