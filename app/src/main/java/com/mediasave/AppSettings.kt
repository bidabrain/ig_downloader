package com.mediasave

import android.content.Context

/**
 * Lightweight global settings, backed by SharedPreferences. Loaded once on startup so plain
 * PlatformHandler objects (which have no Context) can read flags synchronously.
 */
object AppSettings {

    private const val PREFS         = "settings"
    private const val KEY_TP_DOUYIN = "third_party_douyin"

    /** When true, Douyin links are resolved via the third-party parser (greenvideo.cc). */
    @Volatile var thirdPartyDouyin = false
        private set

    fun load(ctx: Context) {
        thirdPartyDouyin = prefs(ctx).getBoolean(KEY_TP_DOUYIN, false)
    }

    fun setThirdPartyDouyin(ctx: Context, enabled: Boolean) {
        thirdPartyDouyin = enabled
        prefs(ctx).edit().putBoolean(KEY_TP_DOUYIN, enabled).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
