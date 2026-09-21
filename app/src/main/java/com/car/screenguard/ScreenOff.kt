package com.car.screenguard

import android.app.Instrumentation
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import java.io.File

/**
 * 一種「把螢幕關掉」的候選做法。
 *
 * @param retired 已在 JHY S17 實測無效（或有副作用），預設不顯示。
 *   不直接刪除是因為舊設定可能還存著這些值，而且換一台車機可能又有用。
 */
enum class LockMethod(
    val code: String,
    val label: String,
    val needsMainThread: Boolean = true,
    val retired: Boolean = false
) {
    // 下面這些實測都不行：A/B 會讓系統真的睡著（音樂導航中斷），
    // C/L 需系統簽章，D/E/G 需 root，F 權限不足，H/I 被這台 ROM 忽略。
    ACC_LOCK("A", "無障礙鎖屏 GLOBAL_ACTION_LOCK_SCREEN", retired = true),
    ADMIN_LOCK("B", "裝置管理員 lockNow()", retired = true),
    POWER_SLEEP("C", "反射呼叫 PowerManager.goToSleep()", retired = true),
    ROOT_POWER_KEY("D", "root：input keyevent 26 (POWER)", false, retired = true),
    ROOT_SLEEP_KEY("E", "root：input keyevent 223 (SLEEP)", false, retired = true),
    SHELL_SLEEP_KEY("F", "免 root：input keyevent 223", false, retired = true),
    BACKLIGHT_SYSFS("G", "背光節點寫 0（/sys/class/backlight）", false, retired = true),
    SCREEN_TIMEOUT("H", "改系統休眠逾時", retired = true),
    BRIGHTNESS_ZERO("I", "系統亮度歸零", retired = true),
    INJECT_POWER_KEY("L", "Instrumentation 注入 POWER 鍵", false, retired = true),

    // 黑幕實測不夠黑（背光關不掉），已不是選項；
    // 不刪是因為沒側錄時還要當備援，只是不再列在測試按鈕裡
    BLACK_OVERLAY("J", "全黑覆蓋層（不夠黑）", retired = true),
    // 廣播這條路可以不試了；掃描若真的抓到有效的 action，
    // 會自動把方法切成 K，那時它自己會重新出現在清單裡
    CUSTOM_BROADCAST("K", "自訂廣播（下面欄位輸入 action）", retired = true),
    CLICK_CAR_BUTTON("M", "點擊車機的關螢幕按鈕（靠節點）"),
    SIMULATE_TAP("N", "模擬點擊側錄位置（正式方案）");

    override fun toString() = "$code. $label"
}

data class LockResult(val ok: Boolean, val msg: String)

object ScreenOff {

    /** 常見中國車機 ROM 的關螢幕廣播候選；不確定哪個對，所以做成逐一掃描。 */
    val PRESET_ACTIONS = listOf(
        // 鼎微 / tx 方案的自訂事件
        "tx.action.SCREEN_OFF",
        "tx.action.ACC_OFF",

        // 掌訊（com.ts）官方命名，最有機會
        "com.ts.intent.action.BACKLIGHT_OFF",
        "com.ts.intent.action.SCREEN_OFF",
        "com.ts.intent.action.SLEEP",
        "com.ts.intent.action.GOTO_SLEEP",

        // 這台車機的 UI 是 com.ts.MainUI，自家 action 也試一輪
        "com.ts.MainUI.SCREEN_OFF",
        "com.ts.MainUI.screenoff",
        "com.ts.action.SCREEN_OFF",
        "com.ts.action.screenoff",
        "com.ts.screenoff",
        "com.ts.SCREEN_OFF",
        "com.ts.MainUI.CLOSE_SCREEN",
        "com.ts.mcu.SCREEN_OFF",
        "com.ts.action.BLACK_SCREEN",

        "android.intent.action.SCREEN_OFF",
        "android.intent.action.CLOSE_SCREEN",
        "com.microntek.screenoff",
        "com.microntek.SCREEN_OFF",
        "com.syu.action.SCREEN_OFF",
        "com.syu.ms.SCREEN_OFF",
        "com.syu.action.SCREENOFF",
        "com.jhy.action.SCREEN_OFF",
        "com.jhy.screenoff",
        "com.hct.screenoff",
        "com.hzbhd.screenoff",
        "com.txznet.screen.off",
        "com.android.action.SCREEN_OFF",
        "com.action.screen.off",
        "com.car.screen.off",
        "action.screen.off",
        "com.wits.screen.off",
        "com.autochips.screenoff"
    )

    private val bg: Handler by lazy {
        val t = HandlerThread("screenoff-bg").apply { start() }
        Handler(t.looper)
    }
    private val main = Handler(Looper.getMainLooper())

    /** 執行一種方法，結果回到主執行緒。 */
    fun run(ctx: Context, method: LockMethod, onResult: (LockResult) -> Unit) {
        val app = ctx.applicationContext
        val job = Runnable {
            val r = runCatching { exec(ctx, app, method) }
                .getOrElse {
                    val e = (it as? java.lang.reflect.InvocationTargetException)?.cause ?: it
                    LockResult(false, "例外：${e.javaClass.simpleName} ${e.message}")
                }
            Logx.d("方法 ${method.code} 結果：${if (r.ok) "OK" else "失敗"} — ${r.msg}")
            main.post { onResult(r) }
        }
        if (method.needsMainThread) main.post(job) else bg.post(job)
    }

    private fun exec(ctx: Context, app: Context, method: LockMethod): LockResult = when (method) {

        LockMethod.ACC_LOCK -> {
            val svc = ScreenGuardService.instance
            when {
                svc == null -> LockResult(false, "無障礙服務尚未啟用")
                Build.VERSION.SDK_INT < 28 -> LockResult(false, "此方法需要 Android 9 以上")
                else -> {
                    val ok = svc.performGlobalAction(8 /* GLOBAL_ACTION_LOCK_SCREEN */)
                    LockResult(ok, "performGlobalAction 回傳 $ok")
                }
            }
        }

        LockMethod.ADMIN_LOCK -> {
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val cn = ComponentName(app, AdminReceiver::class.java)
            if (!dpm.isAdminActive(cn)) LockResult(false, "尚未啟用裝置管理員")
            else {
                dpm.lockNow()
                LockResult(true, "lockNow() 已呼叫")
            }
        }

        LockMethod.POWER_SLEEP -> {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val m = PowerManager::class.java.getMethod("goToSleep", Long::class.javaPrimitiveType)
            m.invoke(pm, SystemClock.uptimeMillis())
            LockResult(true, "goToSleep() 已呼叫")
        }

        LockMethod.ROOT_POWER_KEY -> shell(true, "input keyevent 26")
        LockMethod.ROOT_SLEEP_KEY -> shell(true, "input keyevent 223")
        LockMethod.SHELL_SLEEP_KEY -> shell(false, "input keyevent 223")

        LockMethod.BACKLIGHT_SYSFS -> backlightOff()

        LockMethod.SCREEN_TIMEOUT -> {
            if (!canWriteSettings(app)) LockResult(false, "缺少「修改系統設定」權限")
            else {
                val cur = Settings.System.getInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
                if (Prefs.savedTimeout(app) < 0 && cur > 0) Prefs.setSavedTimeout(app, cur)
                val target = 5000
                Settings.System.putInt(app.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, target)
                LockResult(true, "休眠逾時由 $cur 改成 ${target}ms，若系統照做螢幕會自己暗掉")
            }
        }

        LockMethod.BRIGHTNESS_ZERO -> brightnessZero(app)

        LockMethod.BLACK_OVERLAY -> {
            if (!canDrawOverlay(app)) LockResult(false, "缺少「顯示在其他應用程式上層」權限")
            else BlackOverlay.show(app)
        }

        LockMethod.CUSTOM_BROADCAST -> {
            val action = Prefs.customAction(app).trim()
            if (action.isEmpty()) LockResult(false, "請先在欄位輸入廣播 action")
            else sendAction(app, action)
        }

        LockMethod.INJECT_POWER_KEY -> {
            Instrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_POWER)
            LockResult(true, "POWER 鍵已注入")
        }

        LockMethod.CLICK_CAR_BUTTON ->
            ScreenGuardService.instance?.clickScreenOffButton()
                ?: LockResult(false, "無障礙服務尚未啟用")

        LockMethod.SIMULATE_TAP ->
            ScreenGuardService.instance?.playRecordedTaps()
                ?: LockResult(false, "無障礙服務尚未啟用")
    }

    // === 工具 ===

    fun canWriteSettings(c: Context) =
        Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(c)

    fun canDrawOverlay(c: Context) =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c)

    /** 送出一個廣播 action，順便回報系統裡有沒有人註冊接收。 */
    fun sendAction(c: Context, action: String): LockResult {
        val intent = Intent(action)
        val receivers = runCatching { c.packageManager.queryBroadcastReceivers(intent, 0).size }.getOrDefault(-1)
        return runCatching {
            c.sendBroadcast(intent)
            LockResult(true, "已送出 $action（靜態接收器 $receivers 個）")
        }.getOrElse {
            LockResult(false, "$action 送出失敗：${it.javaClass.simpleName} ${it.message}")
        }
    }

    /** 依序試所有候選廣播，每次間隔後檢查螢幕是否真的關了。 */
    fun scanPresetBroadcasts(c: Context, intervalMs: Long = 2000L, onDone: (String) -> Unit) {
        val app = c.applicationContext
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        // 主執行緒寫、背景執行緒讀，用 Atomic 才安全
        val marked = java.util.concurrent.atomic.AtomicBoolean(false)
        var markedAction: String? = null

        bg.post {
            Logx.d("=== 開始掃描 ${PRESET_ACTIONS.size} 個候選廣播（螢幕若變黑請馬上點一下）===")
            var winner: String? = null
            for ((i, action) in PRESET_ACTIONS.withIndex()) {
                // 點擊標記：這台關螢幕不會讓 isInteractive 變 false，只能靠使用者告訴我們
                ScreenGuardService.instance?.tapMarker = { marked.set(true) }
                markedAction = action

                val r = sendAction(app, action)
                Logx.d("掃描 ${i + 1}/${PRESET_ACTIONS.size}：$action -> ${r.msg}")
                SystemClock.sleep(intervalMs)

                if (marked.get()) {
                    winner = markedAction
                    Logx.d("★★ 你在送出 $winner 之後點了螢幕，這個就是答案 ★★")
                    break
                }
                val interactive = if (Build.VERSION.SDK_INT >= 20) pm.isInteractive else true
                if (!interactive) {
                    winner = action
                    Logx.d("★★ 螢幕在送出 $action 之後關掉了，這個很可能就是答案 ★★")
                    break
                }
            }
            ScreenGuardService.instance?.tapMarker = null

            val summary = winner?.let {
                Prefs.setCustomAction(app, it)
                // K 平常是隱藏的，這裡直接幫使用者切過去，不用再去翻選項
                Prefs.setMethod(app, LockMethod.CUSTOM_BROADCAST)
                "找到了：$it（方法已自動改成 K）"
            } ?: "掃描完成，${PRESET_ACTIONS.size} 個候選都沒反應"
            Logx.d(summary)
            main.post { onDone(summary) }
        }
    }

    /** 還原被方法 H / I 改掉的系統設定。 */
    fun restoreSystemSettings(c: Context): String {
        if (!canWriteSettings(c)) return "缺少「修改系統設定」權限，無法還原"
        val cr = c.contentResolver
        val sb = StringBuilder()
        Prefs.savedTimeout(c).takeIf { it > 0 }?.let {
            Settings.System.putInt(cr, Settings.System.SCREEN_OFF_TIMEOUT, it)
            Prefs.setSavedTimeout(c, -1)
            sb.append("休眠逾時還原為 ${it}ms；")
        }
        Prefs.savedBrightnessMode(c).takeIf { it >= 0 }?.let {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, it)
            Prefs.setSavedBrightnessMode(c, -1)
        }
        Prefs.savedBrightness(c).takeIf { it >= 0 }?.let {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, it)
            Prefs.setSavedBrightness(c, -1)
            sb.append("亮度還原為 $it；")
        }
        if (sb.isEmpty()) return "沒有需要還原的設定"
        Logx.d("還原系統設定：$sb")
        return sb.toString()
    }

    /** 亮度是不是還被我們壓著（用來在 App 重啟時自我修復，避免 App 被殺後螢幕一直暗著）。 */
    fun hasPendingRestore(c: Context) = Prefs.savedBrightness(c) >= 0 || Prefs.savedTimeout(c) > 0

    /**
     * 把系統亮度壓到 0（並切成手動模式，不然自動亮度會馬上把它拉回去）。
     * 原值存進 Prefs，[restoreSystemSettings] 會還原。
     */
    fun brightnessZero(app: Context): LockResult {
        if (!canWriteSettings(app)) return LockResult(false, "缺少「修改系統設定」權限")
        return runCatching {
            val cr = app.contentResolver
            val mode = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, -1)
            val bri = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, -1)
            if (Prefs.savedBrightness(app) < 0 && bri > 0) Prefs.setSavedBrightness(app, bri)
            if (Prefs.savedBrightnessMode(app) < 0 && mode >= 0) Prefs.setSavedBrightnessMode(app, mode)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, 0)
            LockResult(true, "系統亮度 $bri -> 0")
        }.getOrElse { LockResult(false, "系統亮度調整失敗：${it.message}") }
    }

    /** 螢幕真的關掉後，把方法 H 暫時改短的休眠逾時還原回去。 */
    fun restoreIfNeeded(c: Context) {
        val saved = Prefs.savedTimeout(c)
        if (saved <= 0 || !canWriteSettings(c)) return
        runCatching {
            Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, saved)
            Prefs.setSavedTimeout(c, -1)
            Logx.d("休眠逾時已自動還原為 ${saved}ms")
        }
    }

    private val BACKLIGHT_PATHS = listOf(
        "/sys/class/backlight/panel0-backlight/brightness",
        "/sys/class/backlight/backlight/brightness",
        "/sys/class/backlight/lcd-backlight/brightness",
        "/sys/class/leds/lcd-backlight/brightness",
        "/sys/class/backlight/pwm-backlight/brightness",
        "/sys/class/backlight/aml-bl/brightness"
    )

    private fun backlightOff(): LockResult {
        val found = BACKLIGHT_PATHS.filter { File(it).exists() } +
            (File("/sys/class/backlight").listFiles()?.map { "${it.absolutePath}/brightness" } ?: emptyList())
        val paths = found.distinct().filter { File(it).exists() }
        if (paths.isEmpty()) return LockResult(false, "找不到任何背光節點")
        val done = mutableListOf<String>()
        for (p in paths) {
            val direct = runCatching { File(p).writeText("0"); true }.getOrDefault(false)
            if (direct) { done.add("$p(直接寫入)"); continue }
            val r = shell(true, "echo 0 > $p")
            if (r.ok) done.add("$p(su)")
        }
        return if (done.isEmpty()) LockResult(false, "節點存在但寫不進去（需要 root）：${paths.joinToString()}")
        else LockResult(true, "已寫 0 到：${done.joinToString()}")
    }

    /** 跑一行 shell；su=true 時走 root。 */
    private fun shell(su: Boolean, cmd: String): LockResult {
        val argv = if (su) arrayOf("su", "-c", cmd) else arrayOf("sh", "-c", cmd)
        return runCatching {
            val p = Runtime.getRuntime().exec(argv)
            val out = StringBuilder()
            val reader = Thread {
                runCatching { p.inputStream.bufferedReader().forEachLine { out.append(it).append(' ') } }
                runCatching { p.errorStream.bufferedReader().forEachLine { out.append("[err]").append(it).append(' ') } }
            }
            reader.start()
            val code = waitFor(p, 4000)
            reader.join(500)
            val tail = out.toString().trim().take(160)
            if (code == 0) LockResult(true, "`$cmd` 執行成功 $tail")
            else LockResult(false, "`$cmd` exit=$code $tail")
        }.getOrElse {
            LockResult(false, "`$cmd` 無法執行（${if (su) "可能沒有 root" else "權限不足"}）：${it.message}")
        }
    }

    /** minSdk 24 沒有 Process.waitFor(timeout)，自己輪詢。 */
    private fun waitFor(p: Process, timeoutMs: Long): Int {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            try {
                return p.exitValue()
            } catch (e: IllegalThreadStateException) {
                SystemClock.sleep(60)
            }
        }
        runCatching { p.destroy() }
        return -999
    }
}
