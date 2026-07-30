package com.car.screenguard

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 車機上不方便接 adb，所以所有事件都同時寫進「畫面上的記錄區」與檔案，
 * 螢幕關掉再亮起來後仍看得到剛才發生什麼事。
 */
object Logx {
    private const val TAG = "ScreenGuard"
    // 全事件模式一秒可能好幾十筆，緩衝太小的話關鍵那幾行會被沖掉
    private const val MAX_LINES = 1500

    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())

    private var file: File? = null

    /** 設定頁用來即時刷新記錄區；離開頁面要記得設回 null。 */
    @Volatile
    var listener: (() -> Unit)? = null

    @Synchronized
    fun init(c: Context) {
        if (file != null) return
        val f = File(c.applicationContext.filesDir, "screenguard.log")
        file = f
        runCatching {
            if (f.exists()) f.readLines().takeLast(MAX_LINES).forEach { lines.addLast(it) }
        }
    }

    @Synchronized
    fun d(msg: String) {
        Log.d(TAG, msg)
        val line = "${fmt.format(Date())}  $msg"
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
        trimFileIfNeeded()
        main.post { listener?.invoke() }
    }

    @Synchronized
    fun text(): String = if (lines.isEmpty()) "（尚無記錄）" else lines.reversed().joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
        main.post { listener?.invoke() }
    }

    private fun trimFileIfNeeded() {
        val f = file ?: return
        runCatching {
            if (f.length() > 256 * 1024) f.writeText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}
