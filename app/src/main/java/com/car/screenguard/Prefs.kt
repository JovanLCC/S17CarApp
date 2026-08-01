package com.car.screenguard

import android.content.Context

/** 使用者設定的儲存工具。 */
object Prefs {
    private const val P = "screenguard_prefs"

    private const val KEY_ENABLED = "enabled"
    private const val KEY_TRIGGER_VOLUME = "trigger_volume"
    private const val KEY_TRIGGER_SCREEN_ON = "trigger_screen_on"
    private const val KEY_POLL_VOLUME = "poll_volume"
    private const val KEY_DELAY = "delay_ms"
    private const val KEY_METHOD = "lock_method"
    private const val KEY_OTHER_OP_CANCELS = "other_op_cancels"
    private const val KEY_TOAST = "show_toast"
    private const val KEY_CUSTOM_ACTION = "custom_action"
    private const val KEY_SAVED_TIMEOUT = "saved_timeout"
    private const val KEY_SAVED_BRIGHTNESS = "saved_brightness"
    private const val KEY_SAVED_BRIGHTNESS_MODE = "saved_brightness_mode"
    private const val KEY_DIAGNOSTIC = "diagnostic"
    private const val KEY_AUTO_REDARK = "auto_redark"
    private const val KEY_VOL_SETTING_KEYS = "vol_setting_keys"
    private const val KEY_VOL_WINDOW_PKGS = "vol_window_pkgs"
    private const val KEY_VOL_EVENT_PKG = "vol_event_pkg"
    private const val KEY_VOL_EVENT_CLS = "vol_event_cls"
    private const val KEY_REASSERT = "reassert_on_volume"
    private const val KEY_REQUIRE_SCREEN_OFF = "require_screen_off_first"
    private const val KEY_TAP1_X = "tap1_x"
    private const val KEY_TAP1_Y = "tap1_y"
    private const val KEY_TAP2_X = "tap2_x"
    private const val KEY_TAP2_Y = "tap2_y"
    private const val KEY_TAP_GAP = "tap_gap"
    private const val KEY_DROP_ON_NEW_WINDOW = "drop_on_new_window"
    private const val KEY_REVERSE_KEYS = "reverse_keys"
    private const val DEFAULT_REVERSE_KEYS =
        "reverse,rear,backcar,back_car,camera,ccd,avin,cvbs,倒車,倒车,後視,后视,影像"
    private const val KEY_LOG_ALL = "log_everything"
    private const val KEY_CLICK_KEYS = "click_keys"
    private const val DEFAULT_CLICK_KEYS = "關閉螢幕,關螢幕,螢幕關閉,黑屏,息屏,熄屏,關屏,screenoff,screen_off,screen off"
    private const val KEY_OVERLAY_BRIGHTNESS = "overlay_brightness"
    private const val KEY_DIM_SYSTEM = "dim_system"
    private const val KEY_OFF_EVENT_PKG = "off_event_pkg"
    private const val KEY_OFF_EVENT_CLS = "off_event_cls"

    private fun sp(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    /** 總開關：關掉後只剩測試按鈕可用，服務不會自動關螢幕。 */
    fun enabled(c: Context) = sp(c).getBoolean(KEY_ENABLED, true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_ENABLED, v).apply()

    /** 調整音量後啟動倒數（預設開啟，這是主要需求）。 */
    fun triggerVolume(c: Context) = sp(c).getBoolean(KEY_TRIGGER_VOLUME, true)
    fun setTriggerVolume(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_TRIGGER_VOLUME, v).apply()

    /** 螢幕亮起就啟動倒數。JHY S17 抓不到音量事件，所以這台預設要開。 */
    fun triggerScreenOn(c: Context) = sp(c).getBoolean(KEY_TRIGGER_SCREEN_ON, true)
    fun setTriggerScreenOn(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_TRIGGER_SCREEN_ON, v).apply()

    /** 輪詢音量值（車機音量走 MCU、不送廣播時的救命稻草，預設開啟）。 */
    fun pollVolume(c: Context) = sp(c).getBoolean(KEY_POLL_VOLUME, true)
    fun setPollVolume(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_POLL_VOLUME, v).apply()

    /** 倒數毫秒數（預設 5000 = 5 秒）。 */
    fun getDelayMillis(c: Context) = sp(c).getLong(KEY_DELAY, 5000L)
    fun setDelayMillis(c: Context, v: Long) = sp(c).edit().putLong(KEY_DELAY, v).apply()

    /**
     * 正式關螢幕要用哪一個方法。
     * 預設 J（全黑覆蓋層）：實測 JHY S17 上 A/B 會真的斷電關螢幕，
     * 那正是「按音量會被喚醒」的原始狀態，只有假關螢幕能解決這題。
     */
    fun method(c: Context): LockMethod {
        val name = sp(c).getString(KEY_METHOD, LockMethod.BLACK_OVERLAY.name)
        return runCatching { LockMethod.valueOf(name!!) }.getOrDefault(LockMethod.BLACK_OVERLAY)
    }
    fun setMethod(c: Context, m: LockMethod) = sp(c).edit().putString(KEY_METHOD, m.name).apply()

    /** true=其他操作直接取消本次關螢幕；false=其他操作只是把 5 秒重新計時。 */
    fun otherOpCancels(c: Context) = sp(c).getBoolean(KEY_OTHER_OP_CANCELS, true)
    fun setOtherOpCancels(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_OTHER_OP_CANCELS, v).apply()

    /** 倒數開始時跳提示（除錯用，夜間開車建議關）。 */
    fun showToast(c: Context) = sp(c).getBoolean(KEY_TOAST, false)
    fun setShowToast(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_TOAST, v).apply()

    /**
     * 只有「調音量前螢幕是關閉的」才變黑。
     *
     * 預設關閉：JHY S17 實測按關閉螢幕只是切背光，Android 收不到 ACTION_SCREEN_OFF，
     * 全程都認為螢幕是亮的，這個條件在這台永遠不成立。要用的話得先在開發者頁
     * 填好「關閉螢幕按鈕」的畫面事件特徵（見 [screenOffEventPkg]）。
     */
    fun requireScreenOffFirst(c: Context) = sp(c).getBoolean(KEY_REQUIRE_SCREEN_OFF, false)
    fun setRequireScreenOffFirst(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_REQUIRE_SCREEN_OFF, v).apply()

    /**
     * 黑幕的視窗亮度覆寫，存千分比（0~1000）。
     *
     * 0 是 `BRIGHTNESS_OVERRIDE_OFF`（要求關背光），但**這台車機實測設 0 也不會關背光**，
     * 跟 4 沒有差別。預設留 4 只是保守：別台機器若真的吃 0，畫面會全暗，
     * 萬一那時倒車就看不見了。這個值不是黑幕蓋住倒車顯影的原因，
     * 真正原因是圖層順序（見 [Prefs.dropOnNewWindow]）。
     */
    fun overlayBrightness(c: Context) = sp(c).getInt(KEY_OVERLAY_BRIGHTNESS, 4)
    fun setOverlayBrightness(c: Context, v: Int) =
        sp(c).edit().putInt(KEY_OVERLAY_BRIGHTNESS, v.coerceIn(0, 1000)).apply()

    // === 側錄的兩段點擊（方法 N）===
    // 車機左邊那顆懸浮輔助球：第一下展開選單，第二下才是關螢幕的圖示。
    // 錄下這兩個座標後用 dispatchGesture 重播，等於幫使用者按 —— 走的是車機自己的關螢幕，
    // 背光是真的關掉，也不會有黑幕擋住倒車顯影的問題。

    fun tap1(c: Context): Pair<Int, Int> = sp(c).getInt(KEY_TAP1_X, -1) to sp(c).getInt(KEY_TAP1_Y, -1)
    fun tap2(c: Context): Pair<Int, Int> = sp(c).getInt(KEY_TAP2_X, -1) to sp(c).getInt(KEY_TAP2_Y, -1)
    fun setTap1(c: Context, x: Int, y: Int) = sp(c).edit().putInt(KEY_TAP1_X, x).putInt(KEY_TAP1_Y, y).apply()
    fun setTap2(c: Context, x: Int, y: Int) = sp(c).edit().putInt(KEY_TAP2_X, x).putInt(KEY_TAP2_Y, y).apply()
    fun tapsRecorded(c: Context) = tap1(c).first >= 0 && tap2(c).first >= 0
    fun clearTaps(c: Context) = sp(c).edit()
        .putInt(KEY_TAP1_X, -1).putInt(KEY_TAP1_Y, -1)
        .putInt(KEY_TAP2_X, -1).putInt(KEY_TAP2_Y, -1).apply()

    /** 兩下之間隔多久（毫秒），要夠選單展開完。 */
    fun tapGap(c: Context) = sp(c).getLong(KEY_TAP_GAP, 800L)
    fun setTapGap(c: Context, v: Long) = sp(c).edit().putLong(KEY_TAP_GAP, v).apply()

    /**
     * 有新畫面跳到前景時自動撤掉黑幕。
     *
     * 黑幕是 TYPE_APPLICATION_OVERLAY，這個層級永遠蓋在一般 App 視窗之上，
     * 而倒車顯影就是一般 App 畫面 —— 所以黑幕一定會蓋住它，靠亮度或圖層都繞不過去，
     * 只能主動撤掉。倒車顯影、來電、警示都是「系統決定要給你看的東西」，黑幕不該擋著。
     *
     * 預設開啟。這是安全設定，除非你很清楚後果否則不要關。
     */
    fun dropOnNewWindow(c: Context) = sp(c).getBoolean(KEY_DROP_ON_NEW_WINDOW, true)
    fun setDropOnNewWindow(c: Context, v: Boolean) =
        sp(c).edit().putBoolean(KEY_DROP_ON_NEW_WINDOW, v).apply()

    /** 只要畫面事件比對到這些字，一律立刻撤黑幕（倒車相關關鍵字）。 */
    fun reverseKeys(c: Context): List<String> =
        (sp(c).getString(KEY_REVERSE_KEYS, DEFAULT_REVERSE_KEYS) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun reverseKeysRaw(c: Context): String = sp(c).getString(KEY_REVERSE_KEYS, DEFAULT_REVERSE_KEYS) ?: ""
    fun setReverseKeys(c: Context, v: String) = sp(c).edit().putString(KEY_REVERSE_KEYS, v).apply()

    /**
     * 方法 M 要點的按鈕關鍵字（比對文字／說明／viewId，逗號分隔）。
     * 用開發者頁的「傾印畫面節點」找出車機那顆關閉螢幕按鈕實際長什麼樣，再填進來。
     */
    fun clickKeys(c: Context): List<String> =
        (sp(c).getString(KEY_CLICK_KEYS, DEFAULT_CLICK_KEYS) ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun clickKeysRaw(c: Context): String = sp(c).getString(KEY_CLICK_KEYS, DEFAULT_CLICK_KEYS) ?: ""
    fun setClickKeys(c: Context, v: String) = sp(c).edit().putString(KEY_CLICK_KEYS, v).apply()

    /** 蓋黑幕時連系統亮度一起壓到 0（需要「修改系統設定」權限），解除時還原。 */
    fun dimSystem(c: Context) = sp(c).getBoolean(KEY_DIM_SYSTEM, true)
    fun setDimSystem(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_DIM_SYSTEM, v).apply()

    /**
     * 車機「關閉螢幕」按鈕的畫面事件特徵。
     *
     * 這台車機切背光時 Android 收不到 ACTION_SCREEN_OFF，所以改用同一套辦法：
     * 開診斷模式按一次關閉螢幕，從記錄找出那個事件的 pkg / class 填進來，
     * 之後看到這個事件就當成「使用者要暗了」（進入暗模式）。留空＝不啟用。
     */
    fun screenOffEventPkg(c: Context): String = sp(c).getString(KEY_OFF_EVENT_PKG, "") ?: ""
    fun setScreenOffEventPkg(c: Context, v: String) = sp(c).edit().putString(KEY_OFF_EVENT_PKG, v).apply()
    fun screenOffEventCls(c: Context): String = sp(c).getString(KEY_OFF_EVENT_CLS, "") ?: ""
    fun setScreenOffEventCls(c: Context, v: String) = sp(c).edit().putString(KEY_OFF_EVENT_CLS, v).apply()

    /** 黑幕開著又調音量時，把黑幕重貼回最上層蓋掉車機音量條（會閃一下，預設關）。 */
    fun reassertOnVolume(c: Context) = sp(c).getBoolean(KEY_REASSERT, false)
    fun setReassertOnVolume(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_REASSERT, v).apply()

    /**
     * 全事件模式：訂閱 TYPES_ALL_MASK，連畫面內容變化、觸控、按鍵放開通通記錄。
     * 非常吵（車機 UI 一秒可能好幾十筆），只在追訊號時開。
     */
    fun logEverything(c: Context) = sp(c).getBoolean(KEY_LOG_ALL, false)
    fun setLogEverything(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_LOG_ALL, v).apply()

    /** 診斷模式：把所有畫面事件、按鍵、系統設定變化通通記下來，用來找出車機的音量訊號。 */
    fun diagnostic(c: Context) = sp(c).getBoolean(KEY_DIAGNOSTIC, true)
    fun setDiagnostic(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_DIAGNOSTIC, v).apply()

    /** 黑幕被點掉之後，沒操作再過幾秒就自動變回黑幕。 */
    fun autoRedark(c: Context) = sp(c).getBoolean(KEY_AUTO_REDARK, true)
    fun setAutoRedark(c: Context, v: Boolean) = sp(c).edit().putBoolean(KEY_AUTO_REDARK, v).apply()

    /** 哪些系統設定鍵的變化要當成「音量事件」（逗號分隔，比對子字串）。 */
    fun volumeSettingKeys(c: Context): List<String> =
        (sp(c).getString(KEY_VOL_SETTING_KEYS, "volume") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun volumeSettingKeysRaw(c: Context): String = sp(c).getString(KEY_VOL_SETTING_KEYS, "volume") ?: ""
    fun setVolumeSettingKeys(c: Context, v: String) = sp(c).edit().putString(KEY_VOL_SETTING_KEYS, v).apply()

    /** 哪些視窗／套件出現要當成「音量事件」（例如車機自己的音量條，逗號分隔）。 */
    fun volumeWindowPkgs(c: Context): List<String> =
        (sp(c).getString(KEY_VOL_WINDOW_PKGS, "") ?: "")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun volumeWindowPkgsRaw(c: Context): String = sp(c).getString(KEY_VOL_WINDOW_PKGS, "") ?: ""
    fun setVolumeWindowPkgs(c: Context, v: String) = sp(c).edit().putString(KEY_VOL_WINDOW_PKGS, v).apply()

    /**
     * 車機音量條的畫面事件特徵。JHY S17 實測：pkg=com.ts.MainUI、class=SeekBar。
     * 兩個條件同時符合才算音量事件，避免把同一支 App 的其他操作也誤判成音量。
     */
    fun volumeEventPkg(c: Context): String = sp(c).getString(KEY_VOL_EVENT_PKG, "com.ts.MainUI") ?: ""
    fun setVolumeEventPkg(c: Context, v: String) = sp(c).edit().putString(KEY_VOL_EVENT_PKG, v).apply()
    fun volumeEventCls(c: Context): String = sp(c).getString(KEY_VOL_EVENT_CLS, "SeekBar") ?: ""
    fun setVolumeEventCls(c: Context, v: String) = sp(c).edit().putString(KEY_VOL_EVENT_CLS, v).apply()

    /**
     * 一鍵套用車機實測出來的正式方案。
     * 方法 J（假關螢幕）＋ 只靠音量條事件觸發 ＋ 其他操作取消 ＋ 關掉診斷與輪詢。
     */
    fun applyOfficialProfile(c: Context) {
        // 側錄過就用模擬點擊（真的關背光），沒有的話才退回黑幕
        val method = if (tapsRecorded(c)) LockMethod.SIMULATE_TAP else LockMethod.BLACK_OVERLAY
        sp(c).edit()
            .putBoolean(KEY_ENABLED, true)
            .putString(KEY_METHOD, method.name)
            .putBoolean(KEY_TRIGGER_VOLUME, true)
            .putBoolean(KEY_TRIGGER_SCREEN_ON, false)   // 已經抓得到音量，不需要這條備援
            .putBoolean(KEY_POLL_VOLUME, false)         // 這台音量不走 AudioManager，輪詢是白工
            .putBoolean(KEY_OTHER_OP_CANCELS, true)
            .putBoolean(KEY_AUTO_REDARK, false)         // 點掉黑幕後就讓你用，下次按音量再黑
            .putBoolean(KEY_DIAGNOSTIC, false)
            .putBoolean(KEY_TOAST, false)
            // 這台車機的關閉螢幕 Android 看不到，所以正式方案不開這個條件
            .putBoolean(KEY_REQUIRE_SCREEN_OFF, false)
            .putString(KEY_VOL_EVENT_PKG, "com.ts.MainUI")
            .putString(KEY_VOL_EVENT_CLS, "SeekBar")
            .apply()
        if (sp(c).getLong(KEY_DELAY, 0L) <= 0L) setDelayMillis(c, 5000L)
    }

    /** 目前設定是不是就是正式方案。 */
    fun isOfficialProfile(c: Context): Boolean =
        enabled(c) &&
            (method(c) == LockMethod.BLACK_OVERLAY || method(c) == LockMethod.SIMULATE_TAP) &&
            triggerVolume(c) &&
            !triggerScreenOn(c) && !diagnostic(c) &&
            volumeEventPkg(c).isNotEmpty() && volumeEventCls(c).isNotEmpty()

    /** 自訂廣播 action。 */
    fun customAction(c: Context): String = sp(c).getString(KEY_CUSTOM_ACTION, "") ?: ""
    fun setCustomAction(c: Context, v: String) = sp(c).edit().putString(KEY_CUSTOM_ACTION, v).apply()

    // === 方法 H / I 會改系統設定，這裡記住原值以便還原 ===
    fun savedTimeout(c: Context) = sp(c).getInt(KEY_SAVED_TIMEOUT, -1)
    fun setSavedTimeout(c: Context, v: Int) = sp(c).edit().putInt(KEY_SAVED_TIMEOUT, v).apply()
    fun savedBrightness(c: Context) = sp(c).getInt(KEY_SAVED_BRIGHTNESS, -1)
    fun setSavedBrightness(c: Context, v: Int) = sp(c).edit().putInt(KEY_SAVED_BRIGHTNESS, v).apply()
    fun savedBrightnessMode(c: Context) = sp(c).getInt(KEY_SAVED_BRIGHTNESS_MODE, -1)
    fun setSavedBrightnessMode(c: Context, v: Int) = sp(c).edit().putInt(KEY_SAVED_BRIGHTNESS_MODE, v).apply()
}
