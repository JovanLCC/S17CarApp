package com.car.screenguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.media.AudioManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast

/**
 * 核心服務：
 *  1. 偵測音量變化（廣播 / 輪詢 / 實體鍵 / 系統設定鍵 / 車機音量條視窗）→ 啟動 N 秒倒數
 *  2. 倒數期間偵測到「其他操作」→ 依設定取消或重新計時
 *  3. 倒數結束 → 用使用者選定的方法關螢幕（JHY S17 實測只有 J 全黑覆蓋層合用）
 *  4. 診斷模式：把車機端所有動靜記下來，用來找出這台到底靠什麼訊號代表「音量被調整」
 */
class ScreenGuardService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val audio by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    private var armed = false
    private var armedResetOnly = false

    /**
     * 螢幕最後一次亮起的時間。
     *
     * 判斷條件是「**這次調音量之前，螢幕本來是關的**」：
     * 音量事件進來時螢幕還是關的，或螢幕剛亮起不到 [WAKE_GRACE_MS]（＝就是這次音量鍵把它喚醒的），
     * 才算符合。用旗標記「按過關閉螢幕」是不對的 —— 那樣用手指點亮螢幕後調音量也會被誤判。
     */
    private var lastScreenOnAt = 0L

    /**
     * 暗模式：使用者已經表達「我要暗」。
     *
     * 這台車機按關閉螢幕只是切背光，Android 收不到 ACTION_SCREEN_OFF，
     * 所以螢幕狀態不夠用，另外接受「關閉螢幕按鈕的畫面事件」與「黑幕已蓋上」當來源。
     * 點掉黑幕或主動操作螢幕就離開暗模式。
     */
    private var darkMode = false
    private var lastVolumeAt = 0L
    private var suppressUntil = 0L
    private var screenOn = true
    private var volumeSnapshot = mapOf<Int, Int>()
    private var lastWindowsAt = 0L
    private val lastSettingAt = HashMap<String, Long>()
    private val lastLogAllAt = HashMap<String, Long>()
    private var tapCount = 0
    private var lastTapAt = 0L
    private var zeroCount = 0
    private var lastZeroAt = 0L
    private var wasZero = false

    /** 這些 App 的畫面變化不算「使用者操作」（例如調音量時跳出來的音量條）。 */
    private val ignoredPackages = setOf(
        "com.android.systemui", "android", "com.android.settings", BuildInfo.PKG
    )

    /** 只有這些事件型別算「使用者真的在操作」。 */
    private val userActivityTypes = setOf(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
        AccessibilityEvent.TYPE_VIEW_SCROLLED,
        AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
        AccessibilityEvent.TYPE_VIEW_SELECTED,
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    )

    private val watchedStreams = (0..9).toList()

    private val lockRunnable = Runnable {
        armed = false
        val method = Prefs.method(this)
        if (method == LockMethod.BLACK_OVERLAY && BlackOverlay.isShowing()) {
            Logx.d("倒數結束，但黑幕已經在顯示中，略過")
            return@Runnable
        }
        Logx.d("倒數結束 -> 執行關螢幕方法 ${method.code}")
        suppressUntil = SystemClock.uptimeMillis() + 4000
        ScreenOff.run(this, method) { }
    }

    private val reassertRunnable = Runnable {
        if (BlackOverlay.isShowing()) {
            Logx.d("重貼黑幕（把車機音量條蓋回去）")
            BlackOverlay.reassert(applicationContext)
        }
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            checkVolumeChanged("輪詢")
            if (screenOn && Prefs.pollVolume(this@ScreenGuardService)) handler.postDelayed(this, 300)
        }
    }

    /** 車機音量若是寫進系統設定（而不是走 AudioManager），這裡會抓到。 */
    private val settingsObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val key = uri?.lastPathSegment ?: return
            val now = SystemClock.uptimeMillis()
            if (now - (lastSettingAt[key] ?: 0L) < 300) return
            lastSettingAt[key] = now

            if (Prefs.diagnostic(this@ScreenGuardService)) Logx.d("[診斷] 系統設定變更：$uri")
            if (Prefs.volumeSettingKeys(this@ScreenGuardService).any { key.contains(it, true) }) {
                onVolumeChanged("系統設定鍵 $key")
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                VOLUME_CHANGED_ACTION -> {
                    val v = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
                    val prev = intent.getIntExtra("android.media.EXTRA_PREV_VOLUME_STREAM_VALUE", -1)
                    val type = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                    if (v != prev) onVolumeChanged("廣播 stream=$type $prev->$v", v)
                    else if (v >= 0) checkVolumeZeroGesture(v)   // 值沒變＝已經到底了還在按
                    snapshotVolumes()
                }
                Intent.ACTION_SCREEN_ON -> onScreenOn()
                Intent.ACTION_SCREEN_OFF -> onScreenOff()
                Intent.ACTION_USER_PRESENT -> Logx.d("解鎖（USER_PRESENT）")
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Logx.init(this)
        snapshotVolumes()
        registerReceiver(receiver, IntentFilter().apply {
            addAction(VOLUME_CHANGED_ACTION)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        })
        runCatching {
            contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, settingsObserver)
            contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, settingsObserver)
            contentResolver.registerContentObserver(Settings.Secure.CONTENT_URI, true, settingsObserver)
        }
        BlackOverlay.onDismissed = { onOverlayDismissed() }
        BlackOverlay.onShown = { setDarkMode(true, "黑幕已蓋上") }
        TouchWatcher.onTouch = {
            if (Prefs.logEverything(this)) Logx.d("[全] 觸控螢幕")
            onScreenTapped()
            onUserActivity("觸控螢幕")
        }
        // 連點偵測要一直在，不能只有倒數期間才掛
        TouchWatcher.start(applicationContext)
        StateDot.refresh(applicationContext, Prefs.enabled(this))
        lastScreenOnAt = SystemClock.uptimeMillis()
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            screenOn = pm.isInteractive
        }
        handler.post(pollRunnable)
        refreshEventMask()
        Logx.d(
            "=== 服務已連線，方法=${Prefs.method(this).code}，延遲=${Prefs.getDelayMillis(this) / 1000}秒" +
                "，診斷=${if (Prefs.diagnostic(this)) "開" else "關"} ==="
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        runCatching { unregisterReceiver(receiver) }
        runCatching { contentResolver.unregisterContentObserver(settingsObserver) }
        BlackOverlay.onDismissed = null
        BlackOverlay.onShown = null
        // 服務被關掉時不能把螢幕留在全暗狀態，黑幕撤掉、亮度還原
        BlackOverlay.hide(applicationContext, notify = false)
        ScreenOff.restoreSystemSettings(this)
        TouchWatcher.onTouch = null
        TouchWatcher.stopBlocking(applicationContext)
        TouchWatcher.stop(applicationContext)
        StateDot.hide(applicationContext)
        handler.removeCallbacksAndMessages(null)
        Logx.d("=== 服務已中斷 ===")
    }

    override fun onInterrupt() {}

    // === 事件入口 ===

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: ""
        val type = event.eventType

        // 全事件模式：連平常沒訂閱的類型也一起記，用來確認某個動作是不是真的沒有任何事件
        if (Prefs.logEverything(this) && logAllThrottle(type, pkg)) {
            val v = if (event.itemCount > 0) " 值=${event.currentItemIndex}/${event.itemCount}" else ""
            Logx.d(
                "[全] ${AccessibilityEvent.eventTypeToString(type)} pkg=$pkg " +
                    "cls=${event.className?.toString()?.substringAfterLast('.')}$v " +
                    runCatching { event.text?.joinToString(" ")?.take(30) }.getOrNull().orEmpty()
            )
        }

        val cls = event.className?.toString() ?: ""

        // === 安全優先：黑幕不能擋住倒車顯影 ===
        if (BlackOverlay.isShowing()) {
            val hay = "$pkg $cls ${runCatching { event.text?.joinToString(" ") }.getOrNull().orEmpty()}"
            when {
                Prefs.reverseKeys(this).any { hay.contains(it, true) } ->
                    BlackOverlay.dropForSafety(applicationContext, "疑似倒車／攝影畫面：$hay")

                // 系統決定要給你看新畫面（倒車、來電、警示），黑幕就該讓開
                Prefs.dropOnNewWindow(this) &&
                    type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                    pkg.isNotEmpty() && pkg != BuildInfo.PKG ->
                    BlackOverlay.dropForSafety(applicationContext, "有新畫面跳到前景：$pkg")
            }
        }

        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkWindows()
            return
        }

        if (Prefs.diagnostic(this)) {
            val txt = runCatching { event.text?.joinToString(" ")?.take(40) }.getOrNull().orEmpty()
            val value = if (event.itemCount > 0) " 值=${event.currentItemIndex}/${event.itemCount}" else ""
            Logx.d("[診斷] 事件 ${AccessibilityEvent.eventTypeToString(type)} pkg=$pkg cls=${cls.substringAfterLast('.')}$value $txt")
        }

        // 車機的關閉螢幕按鈕：當成「使用者要暗了」，也不能算成一般操作
        if (isScreenOffEvent(pkg, cls)) {
            setDarkMode(true, "按下車機的關閉螢幕")
            return
        }

        // 音量條要在「使用者操作」之前判斷，否則調音量會被當成操作把倒數取消掉
        if (isVolumeEvent(pkg, cls)) {
            val hasValue = event.itemCount > 0
            val value = if (hasValue) event.currentItemIndex else -1
            onVolumeChanged(
                "車機音量條 ${cls.substringAfterLast('.')}" + if (hasValue) "=$value/${event.itemCount}" else "",
                if (hasValue) value else null
            )
            return
        }

        if (pkg in ignoredPackages) return
        if (type !in userActivityTypes) return
        onUserActivity("畫面事件 ${AccessibilityEvent.eventTypeToString(type)} @$pkg")
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        if (Prefs.logEverything(this)) {
            val act = if (event.action == KeyEvent.ACTION_DOWN) "按下" else "放開"
            Logx.d("[全] 按鍵$act keycode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (Prefs.diagnostic(this)) Logx.d("[診斷] 按鍵 keycode=${event.keyCode} (${KeyEvent.keyCodeToString(event.keyCode)})")
        when (event.keyCode) {
            // 音量已經是 0 時系統不會再發音量變化廣播，所以「連按 −」只剩按鍵這條路認得出來。
            // 值要自己讀：這裡是按鍵被處理「之前」，讀到的就是按下去之前的音量，
            // 所以從 1 按到 0 的那一下讀到 1（不算），已經是 0 再按才讀到 0（開始計數）。
            KeyEvent.KEYCODE_VOLUME_DOWN ->
                onVolumeChanged("實體鍵 音量−", keyVolumeValue())

            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_MUTE -> {
                resetZeroGesture()   // 調大或靜音＝放棄這串手勢
                onVolumeChanged("實體鍵 keycode=${event.keyCode}")
            }

            else -> onUserActivity("按鍵 keycode=${event.keyCode}")
        }
        return false // 永遠不攔截，車機原本的功能照常
    }

    /**
     * 這台車機的音量是自家 UI 的 SeekBar 在動（pkg=com.ts.MainUI、class=SeekBar），
     * 完全不經過 AudioManager，所以靠畫面事件的特徵來認。
     * 兩個條件都要符合，同一支 App 的其他操作才不會被誤判成音量。
     */
    private fun isVolumeEvent(pkg: String, cls: String): Boolean {
        val wantPkg = Prefs.volumeEventPkg(this)
        val wantCls = Prefs.volumeEventCls(this)
        if (wantPkg.isEmpty() || wantCls.isEmpty()) return false
        return pkg.contains(wantPkg, true) && cls.contains(wantCls, true)
    }

    /** 車機的音量條通常是一個會冒出來的視窗，這裡把視窗清單記下來找它。 */
    private fun checkWindows() {
        val pkgs = Prefs.volumeWindowPkgs(this)
        val diag = Prefs.diagnostic(this)
        if (!diag && pkgs.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        if (now - lastWindowsAt < 600) return
        lastWindowsAt = now

        val list = runCatching {
            windows.mapNotNull { w ->
                val p = runCatching { w.root?.packageName?.toString() }.getOrNull()
                val t = runCatching { w.title?.toString() }.getOrNull()
                listOfNotNull(p, t).joinToString("/").ifEmpty { "type${w.type}" }
            }
        }.getOrNull() ?: return

        val desc = list.joinToString(" | ")
        if (diag) Logx.d("[診斷] 視窗變化：$desc")
        if (pkgs.any { k -> desc.contains(k, true) }) onVolumeChanged("音量條視窗出現")
    }

    private fun onScreenOn() {
        screenOn = true
        lastScreenOnAt = SystemClock.uptimeMillis()
        val changed = changedStreams()
        Logx.d("螢幕亮起" + if (changed.isNotEmpty()) "（休眠期間音量有變：$changed，判定為音量喚醒）" else "")
        snapshotVolumes()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        if (BlackOverlay.isShowing()) BlackOverlay.reassert(applicationContext)

        if (!Prefs.enabled(this)) return
        if (changed.isNotEmpty() && Prefs.triggerVolume(this)) arm("音量喚醒")
        else if (Prefs.triggerScreenOn(this)) arm("螢幕亮起")
    }

    private fun onScreenOff() {
        screenOn = false
        armed = false
        setDarkMode(true, "螢幕被關閉")
        handler.removeCallbacks(lockRunnable)
        handler.removeCallbacks(pollRunnable)
        snapshotVolumes()
        ScreenOff.restoreIfNeeded(this)
        Logx.d("螢幕已關閉")
    }

    /** @param value 目前音量值，讀不到就傳 null。用來認「音量歸零後連按」的手勢。 */
    private fun onVolumeChanged(source: String, value: Int? = null) {
        lastVolumeAt = SystemClock.uptimeMillis()
        Logx.d("★ 偵測到音量變化（$source）")

        // 手勢判斷要放在所有 return 之前：停用狀態下也得認得出「再做一次」要重新開啟
        if (value != null) checkVolumeZeroGesture(value)

        // 已經是黑幕了就什麼都不用做，不再開一輪倒數
        if (BlackOverlay.isShowing()) {
            if (Prefs.reassertOnVolume(this)) {
                handler.removeCallbacks(reassertRunnable)
                handler.postDelayed(reassertRunnable, 400)
            }
            return
        }

        if (!Prefs.enabled(this) || !Prefs.triggerVolume(this)) return

        // 已經在倒數（這輪本來就是音量喚醒起的頭）：每次調音量都把上一次作廢、從頭數
        if (armed) {
            arm("連續調音量，重新計時")
            return
        }

        if (SystemClock.uptimeMillis() < suppressUntil) {
            Logx.d("剛關過螢幕，略過（防止連環觸發）")
            return
        }

        // 只在「調音量之前螢幕本來是關的」才動作
        if (Prefs.requireScreenOffFirst(this) && !wokenByThisVolume()) {
            val lit = (SystemClock.uptimeMillis() - lastScreenOnAt) / 1000
            Logx.d("調音量前螢幕本來就是亮的（已亮 $lit 秒、非暗模式）-> 不理這次音量")
            return
        }
        arm("音量變化")
    }

    private fun onUserActivity(what: String) {
        if (!armed) return
        // 用螢幕上的音量條調音量時，手指本身也會被算成觸控，
        // 音量事件後的短時間內不當成「其他操作」，否則永遠等不到黑幕
        if (SystemClock.uptimeMillis() - lastVolumeAt < 1200) return

        if (armedResetOnly || !Prefs.otherOpCancels(this)) {
            Logx.d("偵測到其他操作（$what）-> 倒數重新計時")
            arm("操作後重新計時", armedResetOnly)
        } else {
            cancel("其他操作：$what")
            setDarkMode(false, "使用者操作螢幕")
        }
    }

    // === 連點五下切換啟用／停用 ===
    //
    // 開車到不熟的地方需要螢幕一直亮著，這時調音量被關掉螢幕很煩。
    // 用連點是因為開車時不必瞄準，隨便戳都行；懸浮按鈕反而要看一眼才點得到。
    // 第三下先跳提示，讓誤觸的人有機會停手。

    private fun onScreenTapped() {
        if (!Prefs.tapToggle(this)) return
        val now = SystemClock.uptimeMillis()

        // 同一下可能同時被偵測層與攔截層收到，太接近就當重複
        if (now - lastTapAt < 120) return
        // 兩下間隔太久就重新算起
        tapCount = if (now - lastTapAt > TAP_MAX_GAP_MS) 1 else tapCount + 1
        lastTapAt = now
        if (Prefs.diagnostic(this)) Logx.d("觸控偵測：第 $tapCount 下（$TAP_TOGGLE_AT 下切換）")

        if (tapCount in TAP_WARN_AT until TAP_TOGGLE_AT) {
            // 從第三下開始攔截，後兩下就不會誤點到底下的車機 UI
            TouchWatcher.startBlocking(applicationContext)
            handler.removeCallbacks(stopBlockingRunnable)
            handler.postDelayed(stopBlockingRunnable, TAP_MAX_GAP_MS * 2)
        }
        if (gestureProgress(tapCount, "連點五下")) {
            tapCount = 0
            handler.removeCallbacks(stopBlockingRunnable)
            TouchWatcher.stopBlocking(applicationContext)
        }
    }

    /**
     * 連續手勢的共用進度處理：第三、四下提示，第五下切換。
     * 點螢幕與音量鍵兩種手勢都走這裡。
     *
     * @return 是否已經切換（呼叫端要把自己的計數歸零）
     */
    private fun gestureProgress(count: Int, how: String): Boolean {
        if (count < TAP_TOGGLE_AT) return false
        val willEnable = !Prefs.enabled(this)

        Prefs.setEnabled(this, willEnable)
        if (!willEnable) BlackOverlay.hide(applicationContext, notify = false)
        // 不管開或關，都不要讓觸發手勢的那幾下順便把螢幕關掉
        cancel("$how 切換")
        StateDot.refresh(applicationContext, willEnable)
        Logx.d("=== $how -> ${if (willEnable) "開啟" else "關閉"}自動關螢幕 ===")
        showToast(if (willEnable) "🟢 已開啟自動關螢幕" else "🔴 已關閉自動關螢幕")
        return true
    }

    /**
     * 音量已經是 0 之後，再按「−」五次就切換。
     * 手不用離開方向盤，而且音量到底時再按本來就沒作用，不會跟正常操作衝突。
     */
    /**
     * 音量鍵當下控制的是哪個 stream 的音量。
     * 有在播音樂就是媒體音量，否則是鈴聲音量 —— 跟 Android 自己的按鍵路由一致。
     */
    private fun keyVolumeValue(): Int {
        val stream = if (runCatching { audio.isMusicActive }.getOrDefault(false))
            AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING
        return runCatching { audio.getStreamVolume(stream) }.getOrDefault(-1)
    }

    private fun resetZeroGesture() {
        zeroCount = 0
        wasZero = false
    }

    private fun checkVolumeZeroGesture(value: Int) {
        if (!Prefs.volZeroToggle(this)) return
        val now = SystemClock.uptimeMillis()

        if (value < 0) return   // 讀不到值，不能判斷
        if (value > 0) {
            resetZeroGesture()
            return
        }
        // 剛從 1 掉到 0 的那一下不算，要「已經是 0」之後再按才算
        if (!wasZero) {
            wasZero = true
            zeroCount = 0
            lastZeroAt = now
            return
        }
        zeroCount = if (now - lastZeroAt > VOL_GESTURE_GAP_MS) 1 else zeroCount + 1
        lastZeroAt = now
        if (Prefs.diagnostic(this)) Logx.d("音量已為 0，再按第 $zeroCount 下（$TAP_TOGGLE_AT 下切換）")

        if (gestureProgress(zeroCount, "音量歸零後連按")) {
            zeroCount = 0
            wasZero = false
        }
    }

    /**
     * 連點的提示會連續跳，用同一個 Toast 並先取消上一個。
     * 不然它們會排隊，第四下的提示要等第三下播完才出現，跟不上手速。
     */
    /** 只在切換完成時跳一次，所以不需要處理 toast 互相取消的問題。 */
    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private val stopBlockingRunnable = Runnable {
        TouchWatcher.stopBlocking(applicationContext)
        tapCount = 0
    }

    /** 全事件模式很吵，同型別＋同套件 250ms 內只留一筆。 */
    private fun logAllThrottle(type: Int, pkg: String): Boolean {
        val key = "$type/$pkg"
        val now = SystemClock.uptimeMillis()
        if (now - (lastLogAllAt[key] ?: 0L) < 250) return false
        lastLogAllAt[key] = now
        return true
    }

    /**
     * 切換訂閱的事件範圍。XML 設定檔只是初始值，執行中可以用 setServiceInfo 改，
     * 所以全事件模式不必重裝、也不用重開服務。
     */
    fun refreshEventMask() {
        val info = serviceInfo ?: return
        val all = Prefs.logEverything(this)
        info.eventTypes = if (all) AccessibilityEvent.TYPES_ALL_MASK else DEFAULT_EVENT_MASK
        runCatching { serviceInfo = info }
        Logx.d("事件訂閱範圍：${if (all) "全部（很吵，找完記得關）" else "預設"}")
    }

    /**
     * 這次音量事件之前，使用者是不是處於「螢幕關著／我要暗」的狀態。
     * 三種來源任一成立即可：Android 說螢幕是關的、螢幕剛被這次音量喚醒、已進入暗模式。
     */
    private fun wokenByThisVolume(): Boolean =
        !screenOn || SystemClock.uptimeMillis() - lastScreenOnAt < WAKE_GRACE_MS || darkMode

    private fun setDarkMode(v: Boolean, why: String) {
        if (darkMode == v) return
        darkMode = v
        Logx.d(if (v) "→ 進入暗模式（$why）" else "→ 離開暗模式（$why）")
    }

    fun isDarkMode() = darkMode

    /** 車機「關閉螢幕」按鈕的畫面事件特徵（Android 收不到真正的螢幕關閉時的替代訊號）。 */
    private fun isScreenOffEvent(pkg: String, cls: String): Boolean {
        val wantPkg = Prefs.screenOffEventPkg(this)
        val wantCls = Prefs.screenOffEventCls(this)
        if (wantPkg.isEmpty() || wantCls.isEmpty()) return false
        return pkg.contains(wantPkg, true) && cls.contains(wantCls, true)
    }

    /** 給設定頁顯示 Android 這邊看到的螢幕狀態，用來確認車機的關閉螢幕有沒有被 Android 看見。 */
    fun screenStateText(): String =
        if (!screenOn) "關閉"
        else "亮著（已亮 ${(SystemClock.uptimeMillis() - lastScreenOnAt) / 1000} 秒）"

    /** 黑幕被點掉：使用者要看畫面。停手一段時間後再自動黑回去。 */
    private fun onOverlayDismissed() {
        // 黑幕點掉＝使用者要看畫面，離開暗模式；之後調音量就不再符合條件 —— 這就是你要的
        Logx.d("黑幕已解除")
        setDarkMode(false, "黑幕被點掉")
        if (Prefs.enabled(this) && Prefs.autoRedark(this)) {
            arm("黑幕解除，等待自動再黑", resetOnly = true)
        }
    }

    // === 倒數 ===

    @JvmOverloads
    fun arm(reason: String, resetOnly: Boolean = false) {
        val delay = Prefs.getDelayMillis(this)
        armed = true
        armedResetOnly = resetOnly
        handler.removeCallbacks(lockRunnable)
        handler.postDelayed(lockRunnable, delay)
        // 偵測層平常就掛著（連點切換要用），這裡只是保險確認它還在
        TouchWatcher.start(applicationContext)
        Logx.d("啟動倒數 ${delay / 1000} 秒（$reason）")
        if (Prefs.showToast(this)) {
            Toast.makeText(this, "${delay / 1000} 秒後關螢幕", Toast.LENGTH_SHORT).show()
        }
    }

    fun cancel(reason: String) {
        if (!armed) return
        armed = false
        handler.removeCallbacks(lockRunnable)
        Logx.d("倒數取消（$reason）")
    }

    fun isArmed() = armed

    // === 音量快照 ===

    private fun snapshotVolumes() {
        volumeSnapshot = watchedStreams.associateWith {
            runCatching { audio.getStreamVolume(it) }.getOrDefault(-1)
        }
    }

    private fun changedStreams(): String = watchedStreams.mapNotNull { s ->
        val now = runCatching { audio.getStreamVolume(s) }.getOrDefault(-1)
        val old = volumeSnapshot[s] ?: -1
        if (now != old) "stream$s:$old->$now" else null
    }.joinToString()

    private fun checkVolumeChanged(source: String) {
        val changed = changedStreams()
        if (changed.isNotEmpty()) {
            snapshotVolumes()
            onVolumeChanged("$source $changed")
        }
    }

    // === 模擬手指點擊（方法 N）===

    /** 在螢幕座標點一下。走 dispatchGesture，跟真的手指按下去一樣。 */
    fun tap(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) return false
        val path = android.graphics.Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 60)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        return runCatching { dispatchGesture(gesture, null, null) }.getOrDefault(false)
    }

    /** 重播側錄的兩段點擊：先展開車機的輔助球，隔一段時間再點關螢幕的圖示。 */
    fun playRecordedTaps(): LockResult {
        if (!Prefs.tapsRecorded(this)) return LockResult(false, "還沒側錄點擊位置")
        val (x1, y1) = Prefs.tap1(this)
        val (x2, y2) = Prefs.tap2(this)
        val gap = Prefs.tapGap(this)
        val ok = tap(x1, y1)
        Logx.d("模擬點擊第 1 下 ($x1,$y1) 送出=$ok，$gap ms 後點第 2 下")
        handler.postDelayed({
            val ok2 = tap(x2, y2)
            Logx.d("模擬點擊第 2 下 ($x2,$y2) 送出=$ok2")
        }, gap)
        return LockResult(ok, "已模擬點擊 ($x1,$y1) → $gap ms → ($x2,$y2)")
    }

    // === 直接去按車機自己的「關閉螢幕」按鈕 ===
    //
    // 這台車機：真正關螢幕（A/B）會讓系統睡著、音樂導航中斷；系統亮度設定被 ROM 忽略；
    // 又沒有 root 可以寫背光節點。唯一能真正關背光又不中斷播放的，就是車機原廠那顆按鈕，
    // 所以改成用無障礙去點它 —— 跟人手按下去走同一條路。

    /** 找出符合關鍵字的節點並點下去。 */
    fun clickScreenOffButton(): LockResult {
        val keys = Prefs.clickKeys(this)
        if (keys.isEmpty()) return LockResult(false, "沒有設定按鈕關鍵字")

        val roots = mutableListOf<AccessibilityNodeInfo>()
        runCatching { rootInActiveWindow }.getOrNull()?.let { roots.add(it) }
        runCatching { windows.mapNotNull { w -> w.root } }.getOrNull()?.let { roots.addAll(it) }
        if (roots.isEmpty()) return LockResult(false, "讀不到畫面內容（無障礙權限可能沒給完整）")

        for (root in roots) {
            val hit = findNode(root, keys, 0) ?: continue
            val target = clickableSelfOrParent(hit) ?: hit
            val ok = runCatching { target.performAction(AccessibilityNodeInfo.ACTION_CLICK) }.getOrDefault(false)
            val desc = describe(hit)
            if (ok) return LockResult(true, "已點擊 $desc")
            Logx.d("找到 $desc 但點不動，繼續找")
        }
        return LockResult(false, "畫面上找不到可點的「${keys.joinToString("／")}」")
    }

    private fun findNode(node: AccessibilityNodeInfo?, keys: List<String>, depth: Int): AccessibilityNodeInfo? {
        node ?: return null
        if (depth > 25) return null
        val hay = listOfNotNull(
            node.text?.toString(),
            node.contentDescription?.toString(),
            node.viewIdResourceName
        ).joinToString(" ")
        if (hay.isNotEmpty() && keys.any { hay.contains(it, true) }) return node
        for (i in 0 until node.childCount) {
            findNode(runCatching { node.getChild(i) }.getOrNull(), keys, depth + 1)?.let { return it }
        }
        return null
    }

    private fun clickableSelfOrParent(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var n: AccessibilityNodeInfo? = node
        var up = 0
        while (n != null && up < 6) {
            if (n.isClickable) return n
            n = runCatching { n?.parent }.getOrNull()
            up++
        }
        return null
    }

    private fun describe(n: AccessibilityNodeInfo): String =
        "${n.className?.toString()?.substringAfterLast('.')}" +
            "[text=${n.text}, desc=${n.contentDescription}, id=${n.viewIdResourceName?.substringAfterLast('/')}]"

    /**
     * 把目前畫面上的節點全部倒進記錄，用來找出「關閉螢幕」按鈕的實際文字／id。
     * 由設定頁延遲幾秒後呼叫，中間讓使用者切到車機的畫面。
     */
    fun dumpNodes(): Int {
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            n ?: return
            if (count >= 200) return
            val t = n.text?.toString().orEmpty()
            val d = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName?.substringAfterLast('/').orEmpty()
            if (t.isNotEmpty() || d.isNotEmpty() || id.isNotEmpty() || n.isClickable) {
                count++
                Logx.d(
                    "[節點]${" ".repeat(depth.coerceAtMost(8))}" +
                        "${n.className?.toString()?.substringAfterLast('.')} " +
                        "text=$t desc=$d id=$id click=${n.isClickable}"
                )
            }
            for (i in 0 until n.childCount) walk(runCatching { n.getChild(i) }.getOrNull(), depth + 1)
        }

        Logx.d("=== 開始傾印畫面節點 ===")
        val active = runCatching { rootInActiveWindow }.getOrNull()
        val winRoots = runCatching { windows.mapNotNull { w -> w.root } }.getOrNull().orEmpty()
        if (active == null && winRoots.isEmpty()) {
            Logx.d("!! 讀不到任何視窗內容 !!")
            Logx.d("!! 這版加了「讀取畫面內容」權限，請把無障礙服務關掉再打開一次，讓系統重新授權 !!")
            return 0
        }
        active?.let {
            Logx.d("[節點] -- 目前視窗 ${it.packageName} --")
            walk(it, 0)
        }
        winRoots.forEach { r ->
            Logx.d("[節點] -- 視窗 ${r.packageName} --")
            walk(r, 0)
        }
        Logx.d("=== 傾印結束，共 $count 個節點${if (count >= 200) "（已達上限，可能還有更多）" else ""} ===")
        return count
    }

    /** 設定頁「現在的音量值」用，方便人工核對車機有沒有動到 Android 音量。 */
    fun volumeDump(): String = watchedStreams.joinToString(" ") { s ->
        "$s:" + runCatching { audio.getStreamVolume(s) }.getOrDefault(-1)
    }

    companion object {
        const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        /** 螢幕亮起後多久內的音量事件，還算是「這次音量把螢幕喚醒的」。 */
        private const val WAKE_GRACE_MS = 4000L

        /** 連點：兩下之間最多隔多久還算同一串。 */
        private const val TAP_MAX_GAP_MS = 900L

        /** 音量鍵是實體按鍵，按得比點螢幕慢，容許間隔放寬。 */
        private const val VOL_GESTURE_GAP_MS = 2000L
        private const val TAP_WARN_AT = 3
        private const val TAP_TOGGLE_AT = 5

        /** 平常訂閱的事件（對應 accessibility_service_config.xml，全事件模式關掉後要還原成這組）。 */
        private const val DEFAULT_EVENT_MASK =
            AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
                AccessibilityEvent.TYPE_VIEW_SCROLLED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_SELECTED or
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED

        /** 執行中的服務實例，供設定頁測試呼叫。 */
        @Volatile
        var instance: ScreenGuardService? = null
            private set
    }
}

/** 避免直接依賴 BuildConfig（本專案沒開 buildConfig 產生）。 */
object BuildInfo {
    const val PKG = "com.car.screenguard"
}
