package com.chahan.record

import android.content.Context

object Prefs {
    private const val FILE = "record_prefs"
    private const val KEY_AUTO_START = "auto_start"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 開啟 App 後是否立即開始錄音（預設開啟） */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_START, value).apply()
    }
}
