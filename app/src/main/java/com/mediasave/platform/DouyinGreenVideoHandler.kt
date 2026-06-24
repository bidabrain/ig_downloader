package com.mediasave.platform

import android.util.Log
import com.mediasave.AppSettings
import com.mediasave.HandlerContext
import com.mediasave.PlatformHandler
import com.mediasave.WEB_UA
import org.json.JSONObject

private const val GREENVIDEO_HOME = "https://greenvideo.cc/"

/**
 * Alternative Douyin resolver that delegates extraction to the third-party site greenvideo.cc.
 *
 * Replaces the older [DouyinViparseHandler]. Only active when [AppSettings.thirdPartyDouyin] is
 * on; otherwise [matches] returns false and the built-in [DouyinHandler] handles the URL.
 *
 * greenvideo.cc is a Nuxt (Vue) SPA whose extraction API is RSA+AES encrypted, so instead of
 * replicating the API we drive the page itself (route B):
 *  1. initialLoadUrl() redirects the WebView to greenvideo.cc (keeping the Douyin URL as target).
 *  2. onPageFinished() injects JS that:
 *       a. hijacks HTMLAnchorElement.prototype.click + window.open so the moment the page tries to
 *          navigate to a media direct-link we capture it via AndroidBridge.foundMedia and block the
 *          navigation (site/promo links are let through).
 *       b. fills the search input (input.n-input__input-el) with the Douyin URL and clicks 开始.
 *       c. polls the result area, clicking the two-stage download buttons (下载/音频/图片 →
 *          点击下载) which makes the page emit the signed CDN direct-link we capture in (a).
 *
 * The direct-links are byte-signed, watermark-free CDN URLs (e.g. *.ixigua.com) that download with
 * just UA + Range — no Referer or cookie needed.
 */
class DouyinGreenVideoHandler : PlatformHandler {

    override val platformId   = "douyin"
    override val folderName   = "Douyin"
    override val loginUrl     = GREENVIDEO_HOME
    override val cookieDomain: String? = null   // signed CDN direct-links; no cookie needed

    private var targetUrl: String = ""

    override fun matches(url: String) =
        AppSettings.thirdPartyDouyin && DouyinHandler.isDouyinUrl(url)

    override fun onUrlCommitted(url: String) { targetUrl = url }

    // Load greenvideo.cc instead of the Douyin page.
    override fun initialLoadUrl(url: String): String = GREENVIDEO_HOME

    override fun preferredUserAgent(url: String): String? = WEB_UA

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        if (!url.contains("greenvideo.cc")) return
        if (targetUrl.isBlank()) return
        Log.d("DouyinGreenVideo", "injecting automation for $targetUrl")
        ctx.injectJs(automationJs(targetUrl))
        // The SPA may finish loading before Vue has mounted the input; re-inject once as a safety
        // net. The script guards itself, so a second injection is a no-op if the first one ran.
        ctx.postDelayed(2500) { ctx.injectJs(automationJs(targetUrl)) }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) { /* not used */ }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        mapOf(
            "User-Agent"      to WEB_UA,
            "Range"           to "bytes=0-",
            "Accept"          to "*/*",
            "Accept-Language" to "zh-CN,zh;q=0.9"
        )

    private fun automationJs(douyinUrl: String): String {
        val safeUrl = JSONObject.quote(douyinUrl)   // quoted, escaped JS string literal
        return """
            (function(){
                try {
                    if (window.__gvAuto) return;
                    window.__gvAuto = true;
                    var TARGET = $safeUrl;
                    var seen = {};

                    function classify(u){
                        var p = u.split('?')[0].split('#')[0].toLowerCase();
                        if (/\.(mp3|m4a|aac|wav|ogg|flac)$/.test(p)) return 'audio';
                        if (/\.(jpg|jpeg|png|webp|gif|heic|bmp)$/.test(p)) return 'image';
                        return 'video';
                    }
                    function isSiteLink(u){
                        return u.indexOf('greenvideo.cc') >= 0
                            || u.indexOf('feiyudo.com') >= 0
                            || u.indexOf('stringdealer') >= 0;
                    }
                    // Returns true when [u] is an external media link (captured + caller should block).
                    function capture(u){
                        if (!u || u.indexOf('http') !== 0) return false;
                        if (isSiteLink(u)) return false;
                        if (seen[u]) return true;
                        seen[u] = true;
                        try { AndroidBridge.foundMedia(classify(u), u); } catch(e) {}
                        return true;
                    }

                    // (a) hijack anchor click + window.open to intercept the page's download navigation
                    var origClick = HTMLAnchorElement.prototype.click;
                    HTMLAnchorElement.prototype.click = function(){
                        try { if (capture(this.href || '')) return; } catch(e) {}
                        return origClick.apply(this, arguments);
                    };
                    var origOpen = window.open;
                    window.open = function(u){
                        try { if (capture(u)) return null; } catch(e) {}
                        return origOpen.apply(this, arguments);
                    };

                    // (b) fill the search input the way Vue expects, then click 开始
                    function setInput(el, val){
                        try {
                            var d = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                            d.set.call(el, val);
                        } catch(e) { el.value = val; }
                        el.dispatchEvent(new Event('input',  { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                    }
                    function clickByText(selector, texts){
                        var els = document.querySelectorAll(selector);
                        for (var i = 0; i < els.length; i++){
                            var t = (els[i].textContent || '').trim();
                            for (var j = 0; j < texts.length; j++){
                                if (t.indexOf(texts[j]) >= 0){ els[i].click(); return true; }
                            }
                        }
                        return false;
                    }

                    function findInput(){
                        return document.querySelector('input.n-input__input-el')
                            || document.querySelector('input[type="text"]')
                            || document.querySelector('input[type="search"]')
                            || document.querySelector('input');
                    }
                    // greenvideo renders every download button inside a green-tinted row container
                    // (Tailwind class bg-[#85db9e26]): the 视频/音频/图片 rows AND the 图集 list tab.
                    // Collect those buttons (plus any text-matched ones as a fallback). We must NOT
                    // rely on button text: a SINGLE item reads 下载/点击下载, but in a MULTI-item row
                    // (e.g. an image post with many pictures) each stage-1 button is labelled by its
                    // quality/title, not 下载 — so text-matching would miss every image.
                    function resultButtons(){
                        var set = [];
                        function add(b){ if (set.indexOf(b) < 0) set.push(b); }
                        var boxes = document.querySelectorAll('[class*="85db9e26"]');
                        for (var c = 0; c < boxes.length; c++){
                            var bb = boxes[c].querySelectorAll('button');
                            for (var x = 0; x < bb.length; x++) add(bb[x]);
                        }
                        var all = document.querySelectorAll('button');
                        for (var y = 0; y < all.length; y++){
                            var t = (all[y].textContent || '').trim();
                            if (t.indexOf('点击下载') >= 0 || t.indexOf('下载') >= 0 ||
                                t.indexOf('音频') >= 0 || t.indexOf('封面') >= 0) add(all[y]);
                        }
                        return set;
                    }
                    // True once the result rows have rendered (any download button present).
                    function resultsReady(){ return resultButtons().length > 0; }

                    // (c) poll the result area and click the two-stage download buttons
                    var scanCount = 0;
                    function scanOnce(){
                        scanCount++;
                        var btns = resultButtons();
                        for (var i = 0; i < btns.length; i++){
                            var b = btns[i];
                            var t = (b.textContent || '').trim();
                            if (t.indexOf('点击下载') >= 0){
                                b.__gvDl = (b.__gvDl || 0) + 1;
                                if (b.__gvDl <= 3) b.click();          // final stage -> hijack captures direct-link
                            } else if (!b.__gvClicked){
                                b.__gvClicked = true;
                                b.click();                              // first stage -> becomes 点击下载
                            }
                        }
                        if (scanCount < 120) setTimeout(scanOnce, 1000); // poll ~2min
                    }
                    function startScan(){ if (!window.__gvScan){ window.__gvScan = true; scanOnce(); } }

                    // (b) fill the URL + submit, retrying until the result columns appear.
                    // greenvideo's input is a Vue (Naive UI) controlled field: its v-model
                    // updates asynchronously, so we must wait a tick after setting the value
                    // before clicking 开始 — otherwise 开始 reads an empty model and parses nothing.
                    var pumpCount = 0;
                    function pump(){
                        pumpCount++;
                        var input = findInput();
                        if (!input){ if (pumpCount < 30) setTimeout(pump, 500); return; }
                        try { input.focus(); } catch(e) {}
                        setInput(input, TARGET);
                        setTimeout(function(){
                            var el = findInput() || input;
                            if (el.value !== TARGET) setInput(el, TARGET);   // re-fill if the framework wiped it
                            if (!clickByText('button.button-1', ['开始'])) clickByText('button', ['开始','解析','提取']);
                            el.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', keyCode: 13, bubbles: true }));
                            el.dispatchEvent(new KeyboardEvent('keyup',   { key: 'Enter', keyCode: 13, bubbles: true }));
                        }, 800);
                        if (pumpCount < 6) setTimeout(function(){ if (!resultsReady()) pump(); }, 3500);
                    }

                    startScan();
                    pump();
                } catch(e) {}
            })();
        """.trimIndent()
    }
}
