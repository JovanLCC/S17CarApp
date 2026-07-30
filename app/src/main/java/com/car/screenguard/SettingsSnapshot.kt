package com.car.screenguard

import android.content.Context
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * 系統設定快照與比對。
 *
 * 這台車機的關閉螢幕是 MCU 處理的，Android 收不到廣播、也沒有畫面事件。
 * 但車機的系統 App 有可能把狀態寫進某個系統設定鍵 —— 如果有，
 * 那個鍵就是我們唯一能抓到的訊號，甚至可能反過來用寫入來關背光。
 *
 * 用法：按實體鍵之前先「記錄快照」，按完再「與快照比對」，差異就是它動過的東西。
 * 比 ContentObserver 可靠：就算車機沒發通知，前後比對一樣看得出來。
 */
object SettingsSnapshot {

    private const val FILE = "settings_snapshot.txt"

    private val TABLES = listOf(
        "system" to Settings.System.CONTENT_URI,
        "global" to Settings.Global.CONTENT_URI,
        "secure" to Settings.Secure.CONTENT_URI
    )

    /** 讀出目前所有設定鍵值。 */
    fun capture(c: Context): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((tag, uri) in TABLES) out.putAll(readTable(c, tag, uri))
        return out
    }

    private fun readTable(c: Context, tag: String, uri: Uri): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        runCatching {
            c.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                val ni = cur.getColumnIndex("name")
                val vi = cur.getColumnIndex("value")
                if (ni < 0 || vi < 0) return@use
                while (cur.moveToNext()) {
                    val n = runCatching { cur.getString(ni) }.getOrNull() ?: continue
                    out["$tag/$n"] = runCatching { cur.getString(vi) }.getOrNull().orEmpty()
                }
            }
        }
        return out
    }

    fun save(c: Context): Int {
        val map = capture(c)
        val f = File(c.filesDir, FILE)
        runCatching { f.writeText(map.entries.joinToString("\n") { "${it.key}=${it.value}" }) }
        Logx.d("已記錄系統設定快照，共 ${map.size} 個鍵")
        return map.size
    }

    private fun load(c: Context): Map<String, String> {
        val f = File(c.filesDir, FILE)
        if (!f.exists()) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    /** 與上次快照比對，把差異寫進記錄。 */
    fun diff(c: Context): String {
        val old = load(c)
        if (old.isEmpty()) return "還沒有快照，請先按「記錄系統設定快照」"
        val now = capture(c)

        val changed = now.filter { (k, v) -> old.containsKey(k) && old[k] != v }
        val added = now.filterKeys { !old.containsKey(it) }
        val removed = old.filterKeys { !now.containsKey(it) }

        Logx.d("=== 系統設定比對：改 ${changed.size}／新增 ${added.size}／消失 ${removed.size} ===")
        changed.forEach { (k, v) -> Logx.d("[設定變更] $k：${old[k]} -> $v") }
        added.forEach { (k, v) -> Logx.d("[設定新增] $k = $v") }
        removed.forEach { (k, _) -> Logx.d("[設定消失] $k") }
        if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) {
            Logx.d("完全沒有任何設定被動到 —— 這個動作沒有經過 Android 的設定資料庫")
        }
        return "改 ${changed.size}／新增 ${added.size}／消失 ${removed.size}，詳見記錄"
    }
}
