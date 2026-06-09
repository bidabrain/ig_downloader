package com.instadownloader.platform

import com.instadownloader.HandlerContext
import com.instadownloader.MediaItem
import com.instadownloader.PlatformHandler
import com.instadownloader.WEB_UA
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class TwitterHandler : PlatformHandler {

    override val platformId  = "twitter"
    override val folderName  = "Twitter"
    override val loginUrl    = "https://x.com/i/flow/login"
    override val cookieDomain = "https://x.com"

    companion object {
        // Public bearer token embedded in every X web client
        const val X_BEARER   = "Bearer AAAAAAAAAAAAAAAAAAAAANRILgAAAAAAnNwIzUejRCOuH5E6I8xnZz4puTs%3D1Zv7ttfk8LF81IUq16cHjhLTvJu4FA33AGWWjCpTnA"
        const val X_QUERY_ID = "zAz9764BcLZOJ0JU2wrd1A"

        fun tweetIdFrom(url: String): String? =
            Regex("(?:twitter\\.com|x\\.com)/[^/]+/status/(\\d+)").find(url)?.groupValues?.get(1)
    }

    override fun matches(url: String) = url.contains("x.com") || url.contains("twitter.com")

    override fun onPageFinished(url: String, ctx: HandlerContext) {
        val tweetId = tweetIdFrom(url)
        if (tweetId != null) {
            ctx.setStatus("Fetching media for tweet $tweetId…")
            fetchMedia(tweetId, ctx)
        } else {
            ctx.setStatus("Page loaded — press Download All when ready")
        }
    }

    override fun onInterceptRequest(url: String, ctx: HandlerContext) {
        // Twitter media comes from the GraphQL API; CDN interception not needed.
    }

    override fun buildDownloadHeaders(itemUrl: String, cookie: String?): Map<String, String> =
        buildMap {
            put("User-Agent", WEB_UA)
            put("Referer", "https://x.com/")
            if (!cookie.isNullOrBlank()) put("Cookie", cookie)
        }

    // ── API fetching ─────────────────────────────────────────────────────────

    private fun fetchMedia(tweetId: String, ctx: HandlerContext) {
        ctx.runBackground {
            val cookie = ctx.getCookie("https://x.com") ?: ""
            val ct0    = extractCt0(cookie)

            // Prefer authenticated session (works for private accounts)
            var media = if (ct0.isNotEmpty()) tryFetch(tweetId, cookie, ct0, null) else emptyList()

            // Fall back to guest token — works for all public tweets with no login
            if (media.isEmpty()) {
                val guestToken = fetchGuestToken()
                if (guestToken != null) media = tryFetch(tweetId, "", "", guestToken)
            }

            ctx.runOnUi {
                if (media.isNotEmpty()) {
                    media.forEach { ctx.reportMedia(it.type, it.url) }
                    ctx.setStatus("Found ${media.size} item(s) — press Download All to save")
                } else {
                    ctx.setStatus("No media found. Private account? Tap Login, log in, then reload.")
                }
            }
        }
    }

    private fun fetchGuestToken(): String? = try {
        val conn = URL("https://api.twitter.com/1.1/guest/activate.json")
            .openConnection() as HttpURLConnection
        conn.requestMethod  = "POST"
        conn.connectTimeout = 10_000
        conn.readTimeout    = 10_000
        conn.doOutput       = true
        conn.setRequestProperty("Authorization",  X_BEARER)
        conn.setRequestProperty("Content-Length", "0")
        conn.setRequestProperty("User-Agent",     WEB_UA)
        conn.outputStream.close()
        if (conn.responseCode == 200)
            JSONObject(conn.inputStream.bufferedReader().readText())
                .optString("guest_token").takeIf { it.isNotEmpty() }
        else null
    } catch (_: Exception) { null }

    private fun extractCt0(cookie: String): String =
        cookie.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("ct0=") }
            ?.substringAfter("ct0=") ?: ""

    private fun tryFetch(tweetId: String, cookie: String, ct0: String, guestToken: String?): List<MediaItem> {
        val variables    = """{"tweetId":"$tweetId","withCommunity":false,"includePromotedContent":false,"withVoice":false}"""
        val features     = """{"creator_subscriptions_tweet_preview_api_enabled":true,"premium_content_api_read_enabled":false,"communities_web_enable_tweet_community_results_fetch":true,"c9s_tweet_anatomy_moderator_badge_enabled":true,"responsive_web_grok_analyze_button_fetch_trends_enabled":false,"responsive_web_grok_analyze_post_followups_enabled":false,"responsive_web_jetfuel_frame":false,"responsive_web_grok_share_attachment_enabled":true,"articles_preview_enabled":true,"responsive_web_edit_tweet_api_enabled":true,"graphql_is_translatable_rweb_tweet_is_translatable_enabled":true,"view_counts_everywhere_api_enabled":true,"longform_notetweets_consumption_enabled":true,"responsive_web_twitter_article_tweet_consumption_enabled":true,"tweet_awards_web_tipping_enabled":false,"responsive_web_grok_show_grok_translated_post":false,"responsive_web_grok_analysis_button_from_backend":false,"creator_subscriptions_quote_tweet_preview_enabled":false,"freedom_of_speech_not_reach_fetch_enabled":true,"standardized_nudges_misinfo":true,"tweet_with_visibility_results_prefer_gql_limited_actions_policy_enabled":true,"longform_notetweets_rich_text_read_enabled":true,"longform_notetweets_inline_media_enabled":true,"profile_label_improvements_pcf_label_in_post_enabled":true,"rweb_tipjar_consumption_enabled":true,"verified_phone_label_enabled":false,"responsive_web_grok_image_annotation_enabled":true,"responsive_web_graphql_skip_user_profile_image_extensions_enabled":false,"responsive_web_graphql_timeline_navigation_enabled":true,"responsive_web_enhance_cards_enabled":false}"""
        val fieldToggles = """{"withArticleRichContentState":true,"withArticlePlainText":false,"withGrokAnalyze":false,"withDisallowedReplyControls":false}"""
        val enc = "UTF-8"
        val url = "https://x.com/i/api/graphql/$X_QUERY_ID/TweetResultByRestId" +
            "?variables=${URLEncoder.encode(variables, enc)}" +
            "&features=${URLEncoder.encode(features, enc)}" +
            "&fieldToggles=${URLEncoder.encode(fieldToggles, enc)}"

        val json = httpGet(url, cookie, ct0, guestToken) ?: return emptyList()
        return parseResponse(json, tweetId)
    }

    private fun httpGet(url: String, cookie: String, ct0: String, guestToken: String?): String? = try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 12_000
        conn.readTimeout    = 12_000
        conn.setRequestProperty("User-Agent",                WEB_UA)
        conn.setRequestProperty("authorization",             X_BEARER)
        conn.setRequestProperty("x-twitter-active-user",    "yes")
        conn.setRequestProperty("x-twitter-client-language","en")
        conn.setRequestProperty("content-type",             "application/json")
        conn.setRequestProperty("Referer",                  "https://x.com/")
        if (guestToken != null) {
            conn.setRequestProperty("x-guest-token", guestToken)
        } else {
            conn.setRequestProperty("x-csrf-token",        ct0)
            conn.setRequestProperty("x-twitter-auth-type", "OAuth2Session")
            conn.setRequestProperty("Cookie",              cookie)
        }
        if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
    } catch (_: Exception) { null }

    private fun parseResponse(json: String, tweetId: String): List<MediaItem> {
        return try {
            val root   = JSONObject(json)
            val result = root.getJSONObject("data").getJSONObject("tweetResult").getJSONObject("result")
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
                val media = mediaArray.getJSONObject(i); val idx = i + 1
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
                        var bestUrl = ""; var bestBitrate = -1
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
        } catch (_: Exception) { emptyList() }
    }
}
