package com.mediasave

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mediasave.platform.DouyinHandler
import com.mediasave.platform.DouyinGreenVideoHandler
import com.mediasave.platform.InstagramHandler
import com.mediasave.platform.RedNoteHandler
import com.mediasave.platform.TwitterHandler
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: com.google.android.material.appbar.MaterialToolbar
    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var loadBtn: Button
    private lateinit var downloadBtn: Button
    private lateinit var browserBtn: Button
    private lateinit var loginBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    // ── Info-card state views ─────────────────────────────────────────────────
    private lateinit var emptyStateLayout: LinearLayout
    private lateinit var loadingStateLayout: LinearLayout
    private lateinit var mediaFoundLayout: LinearLayout
    private lateinit var downloadProgressSection: LinearLayout
    private lateinit var platformLabel: TextView
    private lateinit var mediaCountLabel: TextView
    private lateinit var mediaTypeLabel: TextView
    private lateinit var downloadStatusLabel: TextView
    private lateinit var downloadProgressBar: ProgressBar
    private var currentInfoState = INFO_IDLE

    // ── Download list ─────────────────────────────────────────────────────────
    private lateinit var mediaListContainer: LinearLayout
    private val downloadItemViews = mutableListOf<ItemViewHolder>()
    private val thumbnailCache = LinkedHashMap<String, Bitmap>()

    private val capturedMedia = LinkedHashMap<String, MediaItem>()
    private var pendingUrl: String? = null
    private var autoLoadRunnable: Runnable? = null
    private var downloadTriggered = false
    private var readyForAutoLoad = false
    private var activeDownloadSession = false
    private var justLoggedIn = false
    private var lastExtractedUrl: String? = null
    private var currentHandler: PlatformHandler? = null
    // Incremented on every new search. Each HandlerContext binds the session id active when
    // it was created, so stale async work (background fetches, delayed JS re-injections) from
    // a previous search is ignored instead of leaking media into the new results.
    private var sessionId = 0

    // ── Platform registry ─────────────────────────────────────────────────────
    private val handlers: List<PlatformHandler> = listOf(
        DouyinGreenVideoHandler(),   // wins over DouyinHandler only when the toggle is on
        DouyinHandler(),
        RedNoteHandler(),
        TwitterHandler(),
        InstagramHandler()
    )

    companion object {
        val EXTRA_HEADERS = mapOf(
            "X-Requested-With" to "",
            "Accept-Language"  to "en-US,en;q=0.9",
            "Accept"           to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        )
        const val ACTION_OPEN_BROWSER = "com.mediasave.OPEN_BROWSER"
        const val EXTRA_BROWSER_URL   = "browser_url"
        const val REQ_WRITE_STORAGE = 1001
        const val INFO_IDLE         = 0
        const val INFO_LOADING      = 1
        const val INFO_READY        = 2
        const val INFO_DOWNLOADING  = 3
    }

    // ── Download list item holder ─────────────────────────────────────────────

    enum class ItemStatus { WAITING, DOWNLOADING, DONE, ERROR }

    inner class ItemViewHolder(
        val item: MediaItem,
        private val checkbox: CheckBox,
        private val spinner: ProgressBar,
        private val icon: ImageView
    ) {
        val isSelected: Boolean get() = checkbox.isChecked
        fun setSelectable(enabled: Boolean) { checkbox.isEnabled = enabled }
        fun setStatus(status: ItemStatus) {
            when (status) {
                ItemStatus.WAITING -> {
                    spinner.visibility = View.GONE
                    icon.visibility    = View.GONE
                }
                ItemStatus.DOWNLOADING -> {
                    spinner.visibility = View.VISIBLE
                    icon.visibility    = View.GONE
                }
                ItemStatus.DONE -> {
                    spinner.visibility = View.GONE
                    icon.setImageResource(R.drawable.ic_check_circle)
                    icon.visibility    = View.VISIBLE
                }
                ItemStatus.ERROR -> {
                    spinner.visibility = View.GONE
                    icon.setImageResource(R.drawable.ic_error_circle)
                    icon.visibility    = View.VISIBLE
                }
            }
        }
    }

    // ── JS bridge ─────────────────────────────────────────────────────────────

    inner class WebBridge {
        @JavascriptInterface
        fun foundMedia(type: String, url: String) {
            makeContext().reportMedia(type, url)
        }

        @JavascriptInterface
        fun foundAppId(id: String) {
            currentHandler?.onFoundAppId(id)
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.load(this)
        setContentView(R.layout.activity_main)

        toolbar                = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        webView                = findViewById(R.id.webView)
        urlInput               = findViewById(R.id.urlInput)
        loadBtn                = findViewById(R.id.loadBtn)
        downloadBtn            = findViewById(R.id.downloadBtn)
        browserBtn             = findViewById(R.id.browserBtn)
        loginBtn               = findViewById(R.id.loginBtn)
        progressBar            = findViewById(R.id.progressBar)
        statusText             = findViewById(R.id.statusText)

        emptyStateLayout       = findViewById(R.id.emptyStateLayout)
        loadingStateLayout     = findViewById(R.id.loadingStateLayout)
        mediaFoundLayout       = findViewById(R.id.mediaFoundLayout)
        downloadProgressSection = findViewById(R.id.downloadProgressSection)
        platformLabel          = findViewById(R.id.platformLabel)
        mediaCountLabel        = findViewById(R.id.mediaCountLabel)
        mediaTypeLabel         = findViewById(R.id.mediaTypeLabel)
        downloadStatusLabel    = findViewById(R.id.downloadStatusLabel)
        downloadProgressBar    = findViewById(R.id.downloadProgressBar)
        mediaListContainer     = findViewById(R.id.mediaListContainer)

        urlInput.setText("")
        setupWebView()

        loadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadUrl(url)
            else Toast.makeText(this, getString(R.string.toast_enter_url), Toast.LENGTH_SHORT).show()
        }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                val url = urlInput.text.toString().trim()
                if (url.isNotEmpty()) loadUrl(url)
                else Toast.makeText(this, getString(R.string.toast_enter_url), Toast.LENGTH_SHORT).show()
                true
            } else false
        }

        urlInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!readyForAutoLoad) return
                val text = s?.toString()?.trim() ?: return
                autoLoadRunnable?.let { urlInput.removeCallbacks(it) }
                if (handlers.any { it.matches(text) }) {
                    val r = Runnable { loadUrl(text) }
                    autoLoadRunnable = r
                    urlInput.postDelayed(r, 600)
                }
            }
        })

        downloadBtn.setOnClickListener { downloadAll() }

        browserBtn.setOnClickListener {
            showBrowser(webView.visibility != View.VISIBLE)
        }

        loginBtn.setOnClickListener {
            val loginUrl = currentHandler?.loginUrl ?: "https://www.instagram.com/accounts/login/"
            webView.loadUrl(loginUrl, EXTRA_HEADERS)
            showBrowser(true)
        }

        if (savedInstanceState == null) {
            val fromHistory = (intent?.flags ?: 0) and
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            if (!fromHistory) intent?.let { handleIntent(it) }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_support -> {
                startActivity(Intent(this, SupportActivity::class.java))
                return true
            }
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() {
        super.onResume()
        readyForAutoLoad = true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleIntent(it) }
    }

    private fun handleIntent(intent: Intent) {
        // Open a specific page in the in-app browser (e.g. greenvideo.cc).
        if (intent.action == ACTION_OPEN_BROWSER) {
            val browserUrl = intent.getStringExtra(EXTRA_BROWSER_URL) ?: return
            webView.settings.userAgentString = WEB_UA
            webView.loadUrl(browserUrl, EXTRA_HEADERS)
            showBrowser(true)
            return
        }
        val url = intent.getStringExtra(Intent.EXTRA_TEXT) ?: intent.data?.toString() ?: return
        if (handlers.any { it.matches(url) }) {
            urlInput.setText(url)
            loadUrl(url)
        }
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

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
            userAgentString      = WEB_UA
            mediaPlaybackRequiresUserGesture = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.addJavascriptInterface(WebBridge(), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") return true
                if (request.method?.uppercase() == "GET") {
                    val targetUrl = request.url.toString()
                    if (targetUrl == view.url) return true
                    view.loadUrl(targetUrl, EXTRA_HEADERS)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                // Don't revert to the loading screen if media is already found and shown.
                // Some handlers (e.g. Douyin) navigate the WebView to a display-only page
                // after extraction (navigateForDisplay) — that must not wipe the results card.
                if (capturedMedia.isEmpty() && currentInfoState != INFO_DOWNLOADING)
                    setInfoState(INFO_LOADING)
            }

            override fun onPageFinished(view: WebView, url: String) {
                CookieManager.getInstance().flush()

                when {
                    isLoginPage(url) -> {
                        justLoggedIn = true
                        statusText.text = getString(R.string.status_login_prompt)
                        setInfoState(INFO_IDLE)
                    }
                    isAfterLogin(url) -> {
                        justLoggedIn = false
                        statusText.text = getString(R.string.status_after_login)
                        setInfoState(INFO_LOADING)
                        pendingUrl?.let { pu ->
                            downloadTriggered = false
                            webView.postDelayed({ webView.loadUrl(pu, EXTRA_HEADERS) }, 500)
                        }
                    }
                    else -> {
                        if (pendingUrl == null) { setInfoState(INFO_IDLE); return }
                        if (downloadTriggered && url == lastExtractedUrl) return
                        downloadTriggered = true
                        lastExtractedUrl  = url
                        currentHandler?.onPageFinished(url, makeContext())
                        // Fallback: if still loading after 8 s with no media, show idle
                        webView.postDelayed({
                            if (capturedMedia.isEmpty() && currentInfoState == INFO_LOADING)
                                setInfoState(INFO_IDLE)
                        }, 8_000)
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) {
                    statusText.text = getString(R.string.status_http_error, errorResponse.statusCode)
                    setInfoState(INFO_IDLE)
                }
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    statusText.text = getString(R.string.status_error, error.description)
                    setInfoState(INFO_IDLE)
                }
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (activeDownloadSession) {
                    val ctx = makeContext()
                    val proxied = currentHandler?.onInterceptRequestFull(request, ctx)
                    if (proxied != null) return proxied
                    currentHandler?.onInterceptRequest(request.url.toString(), ctx)
                }
                return null
            }
        }

        webView.webChromeClient = object : WebChromeClient() {}
    }

    // ── Handler context ───────────────────────────────────────────────────────

    private fun makeContext(): HandlerContext {
        // Bind the session active at creation time. Any callback that fires after a new
        // search started (mySession != sessionId) is from a stale session and is dropped.
        val mySession = sessionId
        return object : HandlerContext {
            override fun reportMedia(type: String, url: String) {
                if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
                if (mySession != sessionId || !activeDownloadSession) return
                val source = currentHandler?.platformId ?: "instagram"
                runOnUiThread {
                    // Re-check on the UI thread: a new search may have started since this was queued.
                    if (mySession != sessionId || !activeDownloadSession) return@runOnUiThread
                    // Dedupe by URL so the visible list stays in sync with the header count.
                    if (capturedMedia.containsKey(url)) return@runOnUiThread
                    val item = MediaItem(type, url, buildFilename(url, type), source)
                    capturedMedia[url] = item
                    updateDownloadHeader()
                    addMediaListItem(item)
                }
            }
            override fun setStatus(msg: String) {
                runOnUiThread {
                    if (mySession != sessionId) return@runOnUiThread
                    statusText.text = msg
                    if (currentInfoState == INFO_LOADING) setInfoState(INFO_IDLE)
                }
            }
            override fun getCookie(domain: String): String?  = CookieManager.getInstance().getCookie(domain)
            override fun injectJs(js: String)                { if (mySession == sessionId) webView.evaluateJavascript(js, null) }
            override fun postDelayed(ms: Long, action: () -> Unit) { webView.postDelayed({ if (mySession == sessionId) action() }, ms) }
            override fun runBackground(action: () -> Unit)   { Thread(action).start() }
            override fun runOnUi(action: () -> Unit)         { runOnUiThread(action) }
            override fun navigateTo(url: String)             { runOnUiThread { if (mySession == sessionId) loadUrl(url) } }
            override fun navigateForDisplay(url: String) {
                runOnUiThread {
                    if (mySession != sessionId) return@runOnUiThread
                    webView.settings.userAgentString = currentHandler?.preferredUserAgent(url)
                    webView.loadUrl(url, EXTRA_HEADERS)
                }
            }
            override fun setUserAgent(ua: String?) { runOnUiThread { if (mySession == sessionId) webView.settings.userAgentString = ua } }
        }
    }

    // ── UI state management ───────────────────────────────────────────────────

    private fun showBrowser(visible: Boolean) {
        webView.visibility = if (visible) View.VISIBLE else View.GONE
        browserBtn.text    = if (visible) getString(R.string.btn_browser_hide) else getString(R.string.btn_browser)
        if (visible) {
            toolbar.setNavigationIcon(R.drawable.ic_arrow_back)
            toolbar.setNavigationOnClickListener { showBrowser(false) }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }
    }

    private fun setInfoState(state: Int) {
        currentInfoState = state
        emptyStateLayout.visibility     = if (state == INFO_IDLE)    View.VISIBLE else View.GONE
        loadingStateLayout.visibility   = if (state == INFO_LOADING) View.VISIBLE else View.GONE
        // Both READY and DOWNLOADING keep the media list card visible
        mediaFoundLayout.visibility     = if (state == INFO_READY || state == INFO_DOWNLOADING) View.VISIBLE else View.GONE
        downloadProgressSection.visibility = if (state == INFO_DOWNLOADING) View.VISIBLE else View.GONE
    }

    private fun platformColorFor(source: String): Int = when (source) {
        "instagram" -> Color.parseColor("#E1306C")
        "rednote"   -> Color.parseColor("#FF2442")
        "twitter"   -> Color.parseColor("#1D9BF0")
        "douyin"    -> Color.parseColor("#2A2A2A")
        else        -> Color.parseColor("#4F46E5")
    }

    private fun platformNameFor(source: String): String = when (source) {
        "instagram" -> "Instagram"
        "rednote"   -> getString(R.string.platform_rednote)
        "twitter"   -> getString(R.string.platform_twitter)
        "douyin"    -> getString(R.string.platform_douyin)
        else        -> source.replaceFirstChar { it.uppercase() }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun isLoginPage(url: String) =
        url.contains("accounts/login") || url.contains("/login") ||
        url.contains("challenge")      || url.contains("accounts/suspended")

    private fun isAfterLogin(url: String): Boolean {
        if (!justLoggedIn) return false
        val trimmed  = url.trimEnd('/')
        val isIgHome = trimmed == "https://www.instagram.com" || trimmed == "https://instagram.com"
        val isXHome  = trimmed == "https://x.com/home"        || trimmed == "https://twitter.com/home"
        val isDyHome = trimmed == "https://www.douyin.com"
        return (isIgHome || isXHome || isDyHome) && pendingUrl != null && pendingUrl != trimmed
    }

    private fun loadUrl(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://"))
            finalUrl = "https://$finalUrl"
        if (finalUrl.startsWith("http://"))
            finalUrl = finalUrl.replaceFirst("http://", "https://")

        currentHandler = handlers.firstOrNull { it.matches(finalUrl) }
        webView.settings.userAgentString = currentHandler?.preferredUserAgent(finalUrl) ?: WEB_UA
        currentHandler?.onUrlCommitted(finalUrl)

        pendingUrl            = finalUrl
        downloadTriggered     = false
        lastExtractedUrl      = null
        activeDownloadSession = true
        // New session: invalidate any in-flight callbacks from the previous search.
        sessionId++

        // Reset media list for new session
        capturedMedia.clear()
        downloadItemViews.clear()
        thumbnailCache.clear()
        mediaListContainer.removeAllViews()

        showBrowser(false)
        // A handler may redirect the initial load (e.g. to a third-party parser site).
        val loadTarget = currentHandler?.initialLoadUrl(finalUrl) ?: finalUrl
        webView.loadUrl(loadTarget, EXTRA_HEADERS)
    }

    /** Update the compact header row (platform pill + count + type). */
    private fun updateDownloadHeader() {
        val n = capturedMedia.size
        if (n == 0) return

        val source = capturedMedia.values
            .groupingBy { it.source }.eachCount()
            .maxByOrNull { it.value }?.key ?: "unknown"
        val types = capturedMedia.values.map { it.type }.distinct()
        val typeText = when {
            "video" in types && "image" in types -> getString(R.string.media_type_mixed)
            "video" in types                     -> getString(R.string.media_type_video)
            "image" in types                     -> getString(R.string.media_type_image)
            "audio" in types                     -> getString(R.string.media_type_audio)
            else                                 -> getString(R.string.media_type_image)
        }

        val color = platformColorFor(source)
        (platformLabel.background.mutate() as? GradientDrawable)?.setColor(color)
        platformLabel.text  = platformNameFor(source)
        mediaCountLabel.text = getString(R.string.media_count, n)
        mediaTypeLabel.text  = typeText

        if (currentInfoState != INFO_DOWNLOADING) setInfoState(INFO_READY)
    }

    /** Inflate one row and append it to the media list. */
    private fun addMediaListItem(item: MediaItem) {
        // Thin divider between rows (not before the first)
        if (mediaListContainer.childCount > 0) {
            val px1 = (resources.displayMetrics.density).toInt().coerceAtLeast(1)
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, px1
                ).also { lp -> lp.marginStart = (16 * resources.displayMetrics.density).toInt() }
                setBackgroundColor(ContextCompat.getColor(this@MainActivity, R.color.divider))
            }
            mediaListContainer.addView(divider)
        }

        val row      = layoutInflater.inflate(R.layout.item_media, mediaListContainer, false)
        val colorBar = row.findViewById<View>(R.id.itemColorBar)
        val checkbox = row.findViewById<CheckBox>(R.id.itemCheckbox)
        val thumbnail = row.findViewById<ImageView>(R.id.itemThumbnail)
        val filename = row.findViewById<TextView>(R.id.itemFilename)
        val platform = row.findViewById<TextView>(R.id.itemPlatform)
        val type     = row.findViewById<TextView>(R.id.itemType)
        val spinner  = row.findViewById<ProgressBar>(R.id.itemProgressBar)
        val icon     = row.findViewById<ImageView>(R.id.itemStatusIcon)

        val color = platformColorFor(item.source)
        colorBar.setBackgroundColor(color)
        checkbox.isChecked = true
        // Tapping anywhere on the row toggles selection too
        row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        filename.text = item.filename
        (platform.background.mutate() as? GradientDrawable)?.setColor(color)
        platform.text = platformNameFor(item.source)
        type.text     = when (item.type) {
            "video" -> getString(R.string.media_type_video)
            "audio" -> getString(R.string.media_type_audio)
            else    -> getString(R.string.media_type_image)
        }

        loadThumbnail(item, thumbnail)

        mediaListContainer.addView(row)
        downloadItemViews.add(ItemViewHolder(item, checkbox, spinner, icon))
    }

    /** Load a thumbnail for [item] into [target] asynchronously, caching the result. */
    private fun loadThumbnail(item: MediaItem, target: ImageView) {
        thumbnailCache[item.url]?.let { target.setImageBitmap(it); return }
        target.setImageDrawable(null)          // show placeholder background until loaded
        target.tag = item.url
        val mySession = sessionId
        Thread {
            val bmp = try { fetchThumbnail(item) } catch (_: Exception) { null } ?: return@Thread
            runOnUiThread {
                if (mySession != sessionId) return@runOnUiThread   // list belongs to an old search
                thumbnailCache[item.url] = bmp
                if (target.tag == item.url) target.setImageBitmap(bmp)
            }
        }.start()
    }

    private fun fetchThumbnail(item: MediaItem): Bitmap? = when (item.type) {
        "video" -> fetchVideoThumbnail(item)
        "audio" -> null                       // audio has no frame to show; keep placeholder
        else    -> fetchImageThumbnail(item)
    }

    private fun thumbHeaders(item: MediaItem): Map<String, String> {
        val handler = handlerFor(item.source)
        val cookie  = handler?.cookieDomain?.let { CookieManager.getInstance().getCookie(it) }
        return handler?.buildDownloadHeaders(item.url, cookie) ?: mapOf("User-Agent" to WEB_UA)
    }

    private fun fetchImageThumbnail(item: MediaItem): Bitmap? {
        val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 15_000
            readTimeout    = 30_000
            thumbHeaders(item).forEach { (k, v) -> setRequestProperty(k, v) }
            connect()
        }
        val code = conn.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) return null
        val bytes = conn.inputStream.use { it.readBytes() }
        val target = (96 * resources.displayMetrics.density).toInt()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val opts = BitmapFactory.Options().apply {
            inSampleSize = calcInSampleSize(bounds.outWidth, bounds.outHeight, target)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun fetchVideoThumbnail(item: MediaItem): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(item.url, thumbHeaders(item))
            retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
    }

    private fun calcInSampleSize(width: Int, height: Int, target: Int): Int {
        if (width <= 0 || height <= 0 || target <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= target && height / (sample * 2) >= target) sample *= 2
        return sample
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadAll() {
        if (downloadItemViews.isEmpty()) {
            statusText.text = getString(R.string.status_no_media)
            setInfoState(INFO_IDLE)
            return
        }

        // Only download the items the user ticked.
        val targets = downloadItemViews.filter { it.isSelected }
        if (targets.isEmpty()) {
            statusText.text = getString(R.string.status_no_selection)
            Toast.makeText(this, getString(R.string.status_no_selection), Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_WRITE_STORAGE)
            return
        }

        val total      = targets.size
        val folderName = handlerFor(targets.firstOrNull()?.item?.source ?: "")?.folderName ?: "Downloads"

        // Stop capturing new media but keep the list so the user can download more later.
        activeDownloadSession = false
        downloadTriggered     = true
        pendingUrl            = null

        downloadBtn.isEnabled        = false
        downloadProgressBar.max      = 100
        downloadProgressBar.progress = 0
        // Lock selection and reset the selected rows to "waiting" before download starts
        downloadItemViews.forEach { it.setSelectable(false) }
        targets.forEach { it.setStatus(ItemStatus.WAITING) }
        setInfoState(INFO_DOWNLOADING)

        Thread {
            var succeeded = 0
            for ((index, holder) in targets.withIndex()) {
                val num = index + 1
                runOnUiThread { holder.setStatus(ItemStatus.DOWNLOADING) }
                try {
                    downloadFileInApp(holder.item) { pct ->
                        runOnUiThread {
                            downloadProgressBar.progress = (index * 100 + pct) / total
                            downloadStatusLabel.text = getString(R.string.status_downloading_progress, num, total, pct)
                        }
                    }
                    succeeded++
                    runOnUiThread { holder.setStatus(ItemStatus.DONE) }
                } catch (e: Exception) {
                    runOnUiThread {
                        holder.setStatus(ItemStatus.ERROR)
                        downloadStatusLabel.text = getString(R.string.status_item_failed, num, e.message)
                    }
                }
            }
            runOnUiThread {
                downloadBtn.isEnabled = true
                downloadItemViews.forEach { it.setSelectable(true) }
                statusText.text = if (succeeded == total)
                    getString(R.string.status_download_complete, total, folderName)
                else
                    getString(R.string.status_download_partial, succeeded, total)
                // Keep list visible on completion; return to idle only clears on next loadUrl
                setInfoState(INFO_READY)
            }
        }.start()
    }

    private fun downloadFileInApp(item: MediaItem, onProgress: (Int) -> Unit) {
        val handler    = handlerFor(item.source)
        val folderName = handler?.folderName ?: "Downloads"
        val cookie     = handler?.cookieDomain?.let { CookieManager.getInstance().getCookie(it) }
        val headers    = handler?.buildDownloadHeaders(item.url, cookie)
            ?: mapOf("User-Agent" to WEB_UA)

        val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
            requestMethod  = "GET"
            connectTimeout = 15_000
            readTimeout    = 60_000
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            connect()
        }
        val responseCode = conn.responseCode
        if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL)
            throw Exception("HTTP $responseCode")

        val totalBytes = conn.contentLengthLong
        val input      = conn.inputStream
        val mimeType   = if (item.type == "video") "video/mp4" else "image/jpeg"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.filename)
                put(MediaStore.Downloads.MIME_TYPE,    mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH,"Download/$folderName")
                put(MediaStore.Downloads.IS_PENDING,   1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw Exception("Cannot create file in MediaStore")
            try {
                contentResolver.openOutputStream(uri)!!.use { out ->
                    streamWithProgress(input, out, totalBytes, onProgress)
                }
                contentResolver.update(uri,
                    ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null); throw e
            }
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                folderName
            )
            dir.mkdirs()
            FileOutputStream(File(dir, item.filename)).use { out ->
                streamWithProgress(input, out, totalBytes, onProgress)
            }
        }
    }

    private fun streamWithProgress(input: InputStream, output: OutputStream, totalBytes: Long, onProgress: (Int) -> Unit) {
        val buf = ByteArray(16 * 1024)
        var downloaded = 0L; var lastPct = -1; var len: Int
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

    private fun handlerFor(platformId: String): PlatformHandler? =
        handlers.firstOrNull { it.platformId == platformId }

    // ── Permissions & misc ────────────────────────────────────────────────────

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_WRITE_STORAGE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            statusText.text = getString(R.string.status_permission_granted)
    }

    private fun buildFilename(url: String, type: String): String {
        val ext = when (type) {
            "video" -> "mp4"
            "audio" -> "mp3"
            else    -> "jpg"
        }
        return try {
            val seg = Uri.parse(url).lastPathSegment?.substringBefore("?")?.takeIf { it.isNotBlank() }
                ?: return "media_${System.currentTimeMillis()}.$ext"
            if ('.' in seg) seg else "$seg.$ext"
        } catch (_: Exception) {
            "media_${System.currentTimeMillis()}.$ext"
        }
    }

    private var backPressedTime = 0L

    override fun onBackPressed() {
        // While the in-app browser is showing, back should leave the browser, not the app.
        if (webView.visibility == View.VISIBLE) {
            if (webView.canGoBack()) webView.goBack() else showBrowser(false)
            return
        }

        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            // Second back gesture: clear caches and fully exit. Files already saved to the
            // phone's Downloads folder are untouched — only temporary app/web cache is wiped.
            sessionId++
            pendingUrl            = null
            activeDownloadSession = false
            capturedMedia.clear()
            urlInput.setText("")
            clearAppCache()
            finishAndRemoveTask()
        } else {
            backPressedTime = now
            Toast.makeText(this, getString(R.string.toast_back_exit), Toast.LENGTH_SHORT).show()
        }
    }

    /** Wipe temporary caches on exit. Login cookies and downloaded files are preserved. */
    private fun clearAppCache() {
        try { webView.clearCache(true) }       catch (_: Exception) {}
        try { webView.clearHistory() }          catch (_: Exception) {}
        try { webView.clearFormData() }         catch (_: Exception) {}
        try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
        thumbnailCache.clear()
        try { cacheDir?.let { clearDirContents(it) } }         catch (_: Exception) {}
        try { externalCacheDir?.let { clearDirContents(it) } } catch (_: Exception) {}
    }

    private fun clearDirContents(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }
}
