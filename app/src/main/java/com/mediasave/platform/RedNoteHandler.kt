package com.mediasave.platform

import com.mediasave.HandlerContext
import com.mediasave.PlatformHandler
import com.mediasave.WEB_UA

class RedNoteHandler : PlatformHandler {

    override val platformId   = "rednote"
    override val folderName   = "RedNote"
    override val loginUrl     = "https://www.xiaohongshu.com/"
    override val cookieDomain: String? = null  // RedNote CDN is public; no cookie needed

    companion object {
        fun isRedNoteUrl(url: String) =
            url.contains("xiaohongshu.com") || url.contains("rednote.com") || url.contains("xhslink.com")
    }

    override fun matches(url: String) = isRedNoteUrl(url)

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        ctx.setStatus("Extracting RedNote media…")
        // __INITIAL_STATE__ may not be populated immediately; retry a few times.
        ctx.injectJs(mediaFinderJs)
        ctx.postDelayed(1500) { ctx.injectJs(mediaFinderJs) }
        ctx.postDelayed(3500) { ctx.injectJs(mediaFinderJs) }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        // Capture direct MP4s from RedNote's video CDN as a fallback to JS extraction.
        if (!url.contains("xhscdn.com")) return
        if (!url.contains("sns-video")) return
        // Skip HLS manifests and segments — these can't be saved as a single file.
        if (url.contains(".m3u8") || url.contains(".ts?") || url.contains("/ts/")) return
        ctx.reportMedia("video", url)
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to WEB_UA,
            "Accept-Language" to "zh-CN,zh;q=0.9"
            // Referer intentionally omitted — RedNote CDN returns 403 with a Referer header.
        )

    // ── JS extraction ─────────────────────────────────────────────────────────
    // Ported from XHS-Downloader (Converter.py, video.py, image.py).

    private val mediaFinderJs = """
        (function() {
            var state = window.__INITIAL_STATE__;
            if (!state) return;

            var note = null;

            // Path 1: phone layout — noteData.data.noteData
            try {
                if (state.noteData && state.noteData.data && state.noteData.data.noteData)
                    note = state.noteData.data.noteData;
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
                // Strategy 1: originVideoKey → direct MP4 on sns-video-bd.xhscdn.com
                try {
                    var key = note.video && note.video.consumer && note.video.consumer.originVideoKey;
                    if (key) {
                        AndroidBridge.foundMedia('video', 'https://sns-video-bd.xhscdn.com/' + key);
                        return;
                    }
                } catch(e) {}

                // Strategy 2: collect h264 + h265 streams, pick highest-resolution direct MP4
                var allStreams = [];
                try { var h264 = note.video.media.stream.h264; if (Array.isArray(h264)) allStreams = allStreams.concat(h264); } catch(e) {}
                try { var h265 = note.video.media.stream.h265; if (Array.isArray(h265)) allStreams = allStreams.concat(h265); } catch(e) {}
                if (allStreams.length > 0) {
                    allStreams.sort(function(a, b) { return (a.height || 0) - (b.height || 0); });
                    var best = allStreams[allStreams.length - 1];
                    var videoUrl = null;
                    if (best.backupUrls && best.backupUrls.length > 0 && best.backupUrls[0].indexOf('.m3u8') < 0)
                        videoUrl = best.backupUrls[0];
                    else if (best.masterUrl && best.masterUrl.indexOf('.m3u8') < 0)
                        videoUrl = best.masterUrl;
                    if (videoUrl) AndroidBridge.foundMedia('video', videoUrl);
                }

                // Strategy 3: DOM fallback — read <video> src directly
                document.querySelectorAll('video').forEach(function(v) {
                    var src = v.currentSrc || v.src || '';
                    if (src && src.indexOf('blob:') < 0 && src.indexOf('data:') < 0 && src.length > 10)
                        AndroidBridge.foundMedia('video', src);
                    v.querySelectorAll('source').forEach(function(s) {
                        if (s.src && s.src.indexOf('blob:') < 0) AndroidBridge.foundMedia('video', s.src);
                    });
                });
            } else {
                // Image post — reconstruct high-quality URL via XHS-Downloader token method
                var images = note.imageList;
                if (!images || !images.length) return;
                images.forEach(function(item) {
                    var url = item.urlDefault || item.url || '';
                    if (!url) return;
                    // token = "/".join(url.split("/")[5:]).split("!")[0]
                    var token = url.split('/').slice(5).join('/').split('!')[0];
                    if (token)
                        AndroidBridge.foundMedia('image',
                            'https://ci.xiaohongshu.com/' + token + '?imageView2/format/jpeg');
                });
            }
        })();
    """.trimIndent()
}
