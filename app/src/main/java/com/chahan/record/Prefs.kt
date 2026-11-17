package com.chahan.record

import android.content.Context

object Prefs {
    const val SRC_MIC = 0
    const val SRC_INTERNAL = 1
    const val SRC_MIX = 2

    private const val FILE = "record_prefs"
    private const val KEY_AUTO_START = "auto_start"
    private const val KEY_SOURCE = "source"
    private const val KEY_SEGMENT = "segment_minutes"
    private const val KEY_SCRIPT_URL = "script_url"
    private const val KEY_SCRIPT_KEY = "script_key"
    private const val KEY_AUTO_BACKUP = "auto_backup"
    private const val KEY_WIFI_ONLY = "wifi_only"
    private const val KEY_LAST_BACKUP = "last_backup"

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

    /** 分段錄音：每隔幾分鐘切成一個檔案，0 表示不分段 */
    fun segmentMinutes(ctx: Context): Int = sp(ctx).getInt(KEY_SEGMENT, 0)

    fun setSegmentMinutes(ctx: Context, value: Int) {
        sp(ctx).edit().putInt(KEY_SEGMENT, value).apply()
    }

    // ---- Google Drive 備份（Apps Script 中繼站）

    fun scriptUrl(ctx: Context): String = sp(ctx).getString(KEY_SCRIPT_URL, "") ?: ""
    fun scriptKey(ctx: Context): String = sp(ctx).getString(KEY_SCRIPT_KEY, "") ?: ""

    fun setScript(ctx: Context, url: String, key: String) {
        sp(ctx).edit().putString(KEY_SCRIPT_URL, url.trim()).putString(KEY_SCRIPT_KEY, key.trim()).apply()
    }

    fun autoBackup(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTO_BACKUP, false)

    fun setAutoBackup(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_AUTO_BACKUP, value).apply()
    }

    fun wifiOnly(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_WIFI_ONLY, true)

    fun setWifiOnly(ctx: Context, value: Boolean) {
        sp(ctx).edit().putBoolean(KEY_WIFI_ONLY, value).apply()
    }

    fun lastBackup(ctx: Context): String = sp(ctx).getString(KEY_LAST_BACKUP, "") ?: ""

    fun setLastBackup(ctx: Context, text: String) {
        sp(ctx).edit().putString(KEY_LAST_BACKUP, text).apply()
    }
}
