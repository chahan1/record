package com.chahan.record

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/** 備份進度與狀態（給畫面顯示用） */
object BackupStatus {
    @Volatile
    var running = false

    @Volatile
    var text = ""
}

data class RemoteFile(val id: String, val name: String, val size: Long, val md5: String)

data class SyncResult(val ok: Boolean, val message: String)

/**
 * 備份到 Google Drive。
 *
 * 做法：Apps Script 中繼站只負責回傳一個短效的 Drive 存取權杖（範圍僅限 drive.file，
 * 也就是只能碰這個 App 建立的檔案）。手機拿到權杖後，直接用 Drive REST API 上傳，
 * 所以權杖過期時會自動重新向中繼站索取，不用再手動登入。
 *
 * 完整性檢查：比對雲端檔案的 md5Checksum 與本機計算值；缺少或不符的會重傳。
 */
class DriveBackup(private val ctx: Context) {

    companion object {
        private const val FOLDER_NAME = "立即錄音機備份"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
    }

    private var token = ""

    // ------------------------------------------------------------ 對外

    /** 測試連線：能取得權杖並找到（或建立）雲端資料夾 */
    fun test(): String {
        return try {
            requireConfigured()
            refreshToken()
            ensureFolder()
            "連線成功，雲端資料夾「$FOLDER_NAME」已就緒"
        } catch (e: Exception) {
            "連線失敗：${e.message}"
        }
    }

    /** 上傳缺少或不一致的錄音，最後再檢查一次雲端是否完整 */
    fun sync(onProgress: (String) -> Unit): SyncResult {
        try {
            requireConfigured()
            onProgress("連線 Google Drive…")
            refreshToken()
            val folderId = ensureFolder()

            val current = RecorderService.currentPath
            val locals = (RecorderService.recordingsDir(ctx)
                .listFiles { f -> f.extension == "m4a" && f.absolutePath != current && f.length() > 0 }
                ?: emptyArray())
                .sortedBy { it.name }

            if (locals.isEmpty()) return SyncResult(true, "沒有可備份的錄音")

            onProgress("檢查雲端檔案…")
            val remote = listRemote(folderId)
            val remoteByName = remote.groupBy { it.name }

            val md5s = HashMap<String, String>()
            var uploaded = 0
            var skipped = 0
            var failed = 0

            locals.forEachIndexed { i, f ->
                onProgress("檢查 ${i + 1}/${locals.size}：${f.name}")
                val md5 = md5Of(f)
                md5s[f.name] = md5
                val sameName = remoteByName[f.name]
                if (sameName != null && sameName.any { it.md5 == md5 && it.size == f.length() }) {
                    skipped++
                    return@forEachIndexed
                }

                onProgress("上傳 ${i + 1}/${locals.size}：${f.name}")
                try {
                    uploadWithRetry(folderId, f)
                    uploaded++
                    // 雲端有同名但內容不同的舊檔，上傳成功後移除
                    sameName?.forEach { deleteRemote(it.id) }
                } catch (e: Exception) {
                    failed++
                }
            }

            onProgress("檢查雲端是否完整…")
            val after = listRemote(folderId)
            val bad = locals.count { f ->
                after.none { it.name == f.name && it.md5 == md5s[f.name] }
            }
            val extra = after.map { it.name }.toSet() - locals.map { it.name }.toSet()

            val sb = StringBuilder()
            sb.append("上傳 $uploaded 個、已是最新 $skipped 個")
            if (failed > 0) sb.append("、失敗 $failed 個")
            sb.append("。\n雲端檢查：")
            if (bad == 0) {
                sb.append("完整（本機 ${locals.size} 個都已備份）")
            } else {
                sb.append("還有 $bad 個不完整，請再按一次備份")
            }
            if (extra.isNotEmpty()) sb.append("\n雲端另有 ${extra.size} 個本機已沒有的檔案（已保留）")
            return SyncResult(bad == 0 && failed == 0, sb.toString())
        } catch (e: Exception) {
            return SyncResult(false, "備份失敗：${e.message}")
        }
    }

    // ------------------------------------------------------------ 權杖

    private fun requireConfigured() {
        if (Prefs.scriptUrl(ctx).isBlank() || Prefs.scriptKey(ctx).isBlank()) {
            throw IOException("尚未設定備份網址與金鑰")
        }
    }

    private fun refreshToken() {
        val base = Prefs.scriptUrl(ctx).trim()
        val sep = if (base.contains("?")) "&" else "?"
        val url = base + sep + "key=" + URLEncoder.encode(Prefs.scriptKey(ctx).trim(), "UTF-8")

        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = 30000
        c.instanceFollowRedirects = true
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""

        if (text.trimStart().startsWith("<")) {
            throw IOException("網址無法使用，請確認部署時「誰可以存取」設為「所有人」")
        }
        if (code !in 200..299) throw IOException("中繼站回應 $code")
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("中繼站回應格式不正確")
        }
        if (!json.optBoolean("ok", false)) throw IOException("金鑰不正確")
        token = json.optString("token")
        if (token.isEmpty()) throw IOException("沒有取得權杖")
    }

    // ------------------------------------------------------------ Drive REST

    private fun call(method: String, url: String, body: String? = null, retryAuth: Boolean = true): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code == 401 && retryAuth) {
            refreshToken()
            return call(method, url, body, false)
        }
        if (code !in 200..299) throw IOException("Drive 回應 $code ${text.take(120)}")
        return text
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun ensureFolder(): String {
        val q = "name = '$FOLDER_NAME' and mimeType = '$FOLDER_MIME' and trashed = false"
        val found = JSONObject(
            call("GET", "$API?q=${enc(q)}&fields=${enc("files(id,name)")}&spaces=drive")
        ).optJSONArray("files")
        if (found != null && found.length() > 0) return found.getJSONObject(0).getString("id")

        val meta = JSONObject().put("name", FOLDER_NAME).put("mimeType", FOLDER_MIME).toString()
        return JSONObject(call("POST", "$API?fields=id", meta)).getString("id")
    }

    private fun listRemote(folderId: String): List<RemoteFile> {
        val out = ArrayList<RemoteFile>()
        var pageToken: String? = null
        do {
            val q = "'$folderId' in parents and trashed = false"
            var url = "$API?q=${enc(q)}&pageSize=1000" +
                "&fields=${enc("nextPageToken,files(id,name,size,md5Checksum)")}"
            if (pageToken != null) url += "&pageToken=${enc(pageToken)}"
            val json = JSONObject(call("GET", url))
            val arr: JSONArray = json.optJSONArray("files") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    RemoteFile(
                        o.getString("id"),
                        o.optString("name"),
                        o.optString("size", "0").toLongOrNull() ?: 0L,
                        o.optString("md5Checksum")
                    )
                )
            }
            pageToken = json.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    private fun deleteRemote(id: String) {
        try {
            call("DELETE", "$API/$id")
        } catch (e: Exception) {
            // 刪除舊檔失敗不影響備份結果
        }
    }

    private fun uploadWithRetry(folderId: String, f: File) {
        var last: Exception? = null
        for (attempt in 1..3) {
            try {
                upload(folderId, f)
                return
            } catch (e: Exception) {
                last = e
                try {
                    Thread.sleep(2000L * attempt)
                } catch (ie: InterruptedException) {
                    break
                }
            }
        }
        throw last ?: IOException("upload failed")
    }

    /** 可續傳上傳（整個檔案串流送出，不會把檔案讀進記憶體） */
    private fun upload(folderId: String, f: File) {
        val len = f.length()

        val start = URL("$UPLOAD?uploadType=resumable&fields=id,name,size,md5Checksum")
            .openConnection() as HttpURLConnection
        start.requestMethod = "POST"
        start.doOutput = true
        start.connectTimeout = 20000
        start.readTimeout = 60000
        start.setRequestProperty("Authorization", "Bearer $token")
        start.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        start.setRequestProperty("X-Upload-Content-Type", "audio/mp4")
        start.setRequestProperty("X-Upload-Content-Length", len.toString())
        val meta = JSONObject()
            .put("name", f.name)
            .put("parents", JSONArray().put(folderId))
            .toString()
        start.outputStream.use { it.write(meta.toByteArray(Charsets.UTF_8)) }

        val startCode = start.responseCode
        if (startCode == 401) {
            refreshToken()
            throw IOException("權杖已更新，重試")
        }
        if (startCode != 200) throw IOException("建立上傳失敗 $startCode")
        val location = start.getHeaderField("Location") ?: throw IOException("沒有取得上傳位址")

        val put = URL(location).openConnection() as HttpURLConnection
        put.requestMethod = "PUT"
        put.doOutput = true
        put.setFixedLengthStreamingMode(len)
        put.connectTimeout = 20000
        put.readTimeout = 300000
        put.setRequestProperty("Content-Type", "audio/mp4")
        FileInputStream(f).use { ins ->
            put.outputStream.use { out -> ins.copyTo(out, 64 * 1024) }
        }
        val code = put.responseCode
        val text = (if (code in 200..299) put.inputStream else put.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw IOException("上傳失敗 $code ${text.take(100)}")
    }

    // ------------------------------------------------------------ 工具

    private fun md5Of(f: File): String {
        val md = MessageDigest.getInstance("MD5")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { String.format("%02x", it) }
    }
}
