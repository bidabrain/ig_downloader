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
import com.instadownloader.platform.DouyinHandler
import com.instadownloader.platform.InstagramHandler
import com.instadownloader.platform.RedNoteHandler
import com.instadownloader.platform.TwitterHandler
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

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
    // Suppresses TextWatcher auto-load during Activity state restoration on app open
    private var readyForAutoLoad = false
    // True only when the user explicitly loaded a URL via loadUrl()
    private var activeDownloadSession = false
    // Set to true only when we actually see a login page
    private var justLoggedIn = false
    // Track the last URL we ran media extraction on
    private var lastExtractedUrl: String? = null
    // The handler responsible for the current URL
    private var currentHandler: PlatformHandler? = null

    // ── Platform registry ─────────────────────────────────────────────────────
    // Order matters: more specific matchers (Douyin) before generic ones (Instagram).
    // To add a new platform: implement PlatformHandler, add it here, update the manifest.
    private val handlers: List<PlatformHandler> = listOf(
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
        const val REQ_WRITE_STORAGE = 1001
    }

    // ── JS bridge ─────────────────────────────────────────────────────────────

    inner class WebBridge {
        @JavascriptInterface
        fun foundMedia(type: String, url: String) {
            // Delegate to the context so all filtering and threading lives in one place.
            makeContext().reportMedia(type, url)
        }

        @JavascriptInterface
        fun foundAppId(id: String) {
            // Platform-specific hook; only InstagramHandler acts on this.
            currentHandler?.onFoundAppId(id)
        }
    }

    // ── Activity lifecycle ────────────────────────────────────────────────────

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

        urlInput.setText("")
        setupWebView()

        loadBtn.setOnClickListener {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) loadUrl(url)
            else Toast.makeText(this, "Enter a URL first", Toast.LENGTH_SHORT).show()
        }

        // Auto-load 600 ms after pasting a recognised URL
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
            val visible = webView.visibility == View.VISIBLE
            webView.visibility = if (visible) View.GONE else View.VISIBLE
            browserBtn.text    = if (visible) "Browser" else "Hide Browser"
        }

        loginBtn.setOnClickListener {
            val loginUrl = currentHandler?.loginUrl ?: "https://www.instagram.com/accounts/login/"
            webView.visibility = View.VISIBLE
            browserBtn.text    = "Hide Browser"
            webView.loadUrl(loginUrl, EXTRA_HEADERS)
        }

        if (savedInstanceState == null) {
            val fromHistory = (intent?.flags ?: 0) and
                Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
            if (!fromHistory) intent?.let { handleIntent(it) }
        }
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

            // Re-apply EXTRA_HEADERS on every GET so sites never see the WebView fingerprint
            // ("X-Requested-With: com.instadownloader") that Android injects by default.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme?.lowercase() ?: ""
                if (scheme != "http" && scheme != "https") return true
                if (request.method?.uppercase() == "GET") {
                    view.loadUrl(request.url.toString(), EXTRA_HEADERS)
                    return true
                }
                return false
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress   = 0
                statusText.text        = "Loading…"
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility = View.GONE
                CookieManager.getInstance().flush()

                when {
                    isLoginPage(url) -> {
                        justLoggedIn = true
                        statusText.text = "Log in — tap Browser to open the login page"
                    }
                    isAfterLogin(url) -> {
                        justLoggedIn = false
                        statusText.text = "Logged in! Loading your link…"
                        pendingUrl?.let { pu ->
                            downloadTriggered = false
                            webView.postDelayed({ webView.loadUrl(pu, EXTRA_HEADERS) }, 500)
                        }
                    }
                    else -> {
                        if (pendingUrl == null) return
                        // URL equality guard lets redirect chains work:
                        // short-link fires onPageFinished (no media found), then the
                        // final URL fires again (different URL → processed fresh).
                        if (downloadTriggered && url == lastExtractedUrl) return
                        downloadTriggered = true
                        lastExtractedUrl  = url
                        currentHandler?.onPageFinished(url, makeContext())
                    }
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame)
                    statusText.text = "HTTP ${errorResponse.statusCode} — tap Load to retry"
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame)
                    statusText.text = "Error: ${error.description}"
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

        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
            }
        }
    }

    // ── Handler context ───────────────────────────────────────────────────────

    /** Create the HandlerContext adapter that connects handlers to this Activity. */
    private fun makeContext(): HandlerContext = object : HandlerContext {
        override fun reportMedia(type: String, url: String) {
            if (url.isBlank() || url.startsWith("blob:") || url.startsWith("data:")) return
            if (!activeDownloadSession) return
            val source = currentHandler?.platformId ?: "instagram"
            // Always post to the UI thread — this may be called from any thread.
            runOnUiThread {
                capturedMedia[url] = MediaItem(type, url, buildFilename(url, type), source)
                updateDownloadButton()
            }
        }
        override fun setStatus(msg: String)              { statusText.text = msg }
        override fun getCookie(domain: String): String?  = CookieManager.getInstance().getCookie(domain)
        override fun injectJs(js: String)                { webView.evaluateJavascript(js, null) }
        override fun postDelayed(ms: Long, action: () -> Unit) { webView.postDelayed(action, ms) }
        override fun runBackground(action: () -> Unit)   { Thread(action).start() }
        override fun runOnUi(action: () -> Unit)         { runOnUiThread(action) }
        override fun navigateTo(url: String)             { runOnUiThread { loadUrl(url) } }
        override fun setUserAgent(ua: String?)           { runOnUiThread { webView.settings.userAgentString = ua } }
    }

    // ── Navigation helpers ────────────────────────────────────────────────────

    private fun isLoginPage(url: String) =
        url.contains("accounts/login") || url.contains("/login") ||
        url.contains("challenge")      || url.contains("accounts/suspended")

    private fun isAfterLogin(url: String): Boolean {
        if (!justLoggedIn) return false
        val trimmed   = url.trimEnd('/')
        val isIgHome  = trimmed == "https://www.instagram.com" || trimmed == "https://instagram.com"
        val isXHome   = trimmed == "https://x.com/home"        || trimmed == "https://twitter.com/home"
        val isDyHome  = trimmed == "https://www.douyin.com"
        return (isIgHome || isXHome || isDyHome) && pendingUrl != null && pendingUrl != trimmed
    }

    private fun loadUrl(url: String) {
        var finalUrl = url
        if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://"))
            finalUrl = "https://$finalUrl"
        if (finalUrl.startsWith("http://"))
            finalUrl = finalUrl.replaceFirst("http://", "https://")
        // Reset UA to default before each new navigation so a previous handler's
        // custom UA (e.g. Douyin desktop UA) doesn't bleed into other platforms.
        webView.settings.userAgentString = null
        currentHandler    = handlers.firstOrNull { it.matches(finalUrl) }
        currentHandler?.onUrlCommitted(finalUrl)
        pendingUrl        = finalUrl
        downloadTriggered = false
        lastExtractedUrl  = null
        activeDownloadSession = true
        capturedMedia.clear()
        updateDownloadButton()
        webView.visibility = View.GONE
        browserBtn.text    = "Browser"
        webView.loadUrl(finalUrl, EXTRA_HEADERS)
    }

    private fun updateDownloadButton() {
        val n = capturedMedia.size
        downloadBtn.text = if (n > 0) "Download All ($n found)" else "Download All"
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun downloadAll() {
        if (capturedMedia.isEmpty()) {
            statusText.text = "No media found — make sure you are logged in"
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

        val items      = capturedMedia.values.toList()
        val total      = items.size
        val folderName = handlerFor(items.firstOrNull()?.source ?: "")?.folderName ?: "Downloads"

        activeDownloadSession = false
        downloadTriggered     = true
        pendingUrl            = null
        capturedMedia.clear()
        updateDownloadButton()

        downloadBtn.isEnabled  = false
        progressBar.max        = 100
        progressBar.progress   = 0
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
                    runOnUiThread { statusText.text = "第 $num 个失败：${e.message}" }
                }
            }
            runOnUiThread {
                progressBar.visibility = View.GONE
                downloadBtn.isEnabled  = true
                statusText.text = if (succeeded == total)
                    "下载完成，共 $total 个文件，保存至 下载/$folderName/"
                else
                    "完成：$succeeded/$total 成功"
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
            statusText.text = "权限已获取，请再次点击下载"
    }

    private fun buildFilename(url: String, type: String): String {
        val ext = if (type == "video") "mp4" else "jpg"
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
        val now = System.currentTimeMillis()
        if (now - backPressedTime < 2000) {
            urlInput.setText("")
            pendingUrl            = null
            activeDownloadSession = false
            capturedMedia.clear()
            super.onBackPressed()
        } else {
            backPressedTime = now
            Toast.makeText(this, "再按一次退出", Toast.LENGTH_SHORT).show()
        }
    }
}
