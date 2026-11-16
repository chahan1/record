package com.chahan.record

import android.content.Context

object Prefs {
    const val SRC_MIC = 0
    const val SRC_INTERNAL = 1
    const val SRC_MIX = 2

    private const val FILE = "record_prefs"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_SOURCE = "source"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** 開啟 App 後是否立即開始錄音（預設開啟） */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_START, true)

    fun setAutoStart(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_START, value).apply()
    }

    /** 錄音來源：麥克風／其他 App 的聲音／兩者混合 */
    fun source(ctx: Context): Int = sp(ctx).getInt(KEY_SOURCE, SRC_MIC)

    fun setSource(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_SOURCE, value).apply()
    }
}
