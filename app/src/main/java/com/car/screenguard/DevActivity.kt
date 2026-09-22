package com.car.screenguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

/**
 * 開發者／測試介面：12 種關螢幕方法、掃描工具、全部細項設定、事件記錄。
 * 一般使用只需要主畫面（[MainActivity]）的秒數 + 開始使用。
 */
class DevActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var logContainer: LinearLayout
    private var lastLogRenderAt = 0L
    private lateinit var editDelay: EditText
    private lateinit var editAction: EditText

    private val adminComponent by lazy { ComponentName(this, AdminReceiver::class.java) }
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logContainer = findViewById(R.id.logContainer)
        editDelay = findViewById(R.id.editDelay)
        editAction = findViewById(R.id.editAction)

        setupOfficialButton()
        setupPermissionButtons()
        setupTestButtons()
        setupSettings()
        setupLogButtons()
        hideRetired()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshLog()
        Logx.listener = { refreshLog() }
    }

    override fun onPause() {
        super.onPause()
        Logx.listener = null
    }

    // === 一鍵正式啟用 ===

    private fun setupOfficialButton() {
        findViewById<Button>(R.id.btnOfficial).setOnClickListener {
            if (!isAccessibilityEnabled()) {
                toast("請先啟用無障礙服務，不然偵測不到音量條")
                return@setOnClickListener
            }
            if (!ScreenOff.canDrawOverlay(this)) {
                toast("請先允許「顯示在其他 App 上層」，黑幕才蓋得起來")
                return@setOnClickListener
            }
            Prefs.applyOfficialProfile(this)
            Logx.d(
                "=== 已套用正式方案：方法 ${Prefs.method(this).code} ＋ 音量條 " +
                    "${Prefs.volumeEventPkg(this)}/${Prefs.volumeEventCls(this)} " +
                    "＋ ${Prefs.getDelayMillis(this) / 1000} 秒 ＋ 其他操作取消 ==="
            )
            toast("已正式啟用，現在可以關掉 App，它會在背景運作")
            recreate()
        }
    }

    // === ① 權限 ===

    private fun setupPermissionButtons() {
        findViewById<Button>(R.id.btnAccessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            toast("找到「車機螢幕守衛」把它打開")
        }

        findViewById<Button>(R.id.btnAdmin).setOnClickListener {
            if (dpm.isAdminActive(adminComponent)) {
                toast("裝置管理員已啟用")
            } else {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用來以 lockNow() 關閉螢幕")
                }
                startActivityCatch(intent, "此車機沒有裝置管理員設定頁")
            }
        }

        findViewById<Button>(R.id.btnOverlayPerm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivityCatch(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
                    "此車機沒有懸浮視窗設定頁"
                )
            } else toast("此系統版本預設已允許")
        }

        findViewById<Button>(R.id.btnWritePerm).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 23) {
                startActivityCatch(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")),
                    "此車機沒有修改系統設定的頁面"
                )
            } else toast("此系統版本預設已允許")
        }
    }

    /**
     * 把實測無效的東西收起來。畫面上剩下的都是還有用的，
     * 下車測試時不用再猜該按哪一顆。
     */
    private fun hideRetired() {
        if (Prefs.showAllMethods(this)) return
        intArrayOf(
            R.id.editAction,        // 方法 K 的輸入欄
            R.id.btnAdmin,          // 方法 B 專用
            R.id.btnWritePerm,      // 方法 H/I 與「壓亮度」專用，都無效
            R.id.btnRestore,        // 上面那些不改設定了，就不需要還原
            R.id.snapshotHint,      // 設定快照比對：這台關螢幕沒寫任何系統設定
            R.id.snapshotRow,
            R.id.brightnessRow,     // 黑幕亮度：實測設 0 也不會關背光
            R.id.editVolKeys,       // 音量沒寫系統設定
            R.id.editVolWindows,    // 音量條是畫面事件，不是獨立視窗
            R.id.btnVolumeDump      // 這台不走 AudioManager，印出來永遠不變
        ).forEach { findViewById<View>(it)?.visibility = View.GONE }
    }

    /** 默認只列還在用的方法；目前選定的那個一定要在清單裡，不然下拉會指錯。 */
    private fun visibleMethods(): List<LockMethod> {
        val cur = Prefs.method(this)
        return LockMethod.values().filter {
            Prefs.showAllMethods(this) || !it.retired || it == cur
        }
    }

    // === ② 方法測試 ===

    private fun setupTestButtons() {
        val container = findViewById<LinearLayout>(R.id.containerTests)
        visibleMethods().forEach { method ->
            val b = Button(this).apply {
                text = "測試 ${method.code}：${method.label}"
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    if (method == LockMethod.CUSTOM_BROADCAST) {
                        Prefs.setCustomAction(this@DevActivity, editAction.text.toString())
                    }
                    Logx.d("【手動測試】方法 ${method.code} ${method.label}")
                    ScreenOff.run(this@DevActivity, method) { r ->
                        toast("${method.code}：${if (r.ok) "成功" else "失敗"} ${r.msg}")
                        updateStatus()
                    }
                }
            }
            container.addView(b)
        }

        editAction.setText(Prefs.customAction(this))

        val editClickKeys = findViewById<EditText>(R.id.editClickKeys)
        editClickKeys.setText(Prefs.clickKeysRaw(this))
        editClickKeys.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.setClickKeys(this, editClickKeys.text.toString())
        }

        findViewById<Button>(R.id.btnLearn).setOnClickListener {
            val svc = ScreenGuardService.instance
            if (svc == null) {
                toast("請先啟用無障礙服務")
            } else {
                svc.startLearnButton()
                toast("現在去展開輔助球，點那顆關螢幕圖示，我會自動記住它")
                moveTaskToBack(true)
            }
        }

        findViewById<Button>(R.id.btnDump).setOnClickListener {
            Prefs.setClickKeys(this, editClickKeys.text.toString())
            val svc = ScreenGuardService.instance
            if (svc == null) {
                toast("請先啟用無障礙服務")
            } else {
                toast("8 秒後傾印：請馬上把車機的輔助球展開，讓關螢幕圖示露出來")
                android.os.Handler(mainLooper).postDelayed({
                    // 只印車機自家的畫面；留空則印所有（自家 App 永遠排除）
                    val filter = findViewById<EditText>(R.id.editOffEventPkg).text.toString().trim()
                    val n = svc.dumpNodes(filter)
                    Logx.d("傾印完成，$n 個節點；找 click=true 的那幾行，把 id 填進上面的按鈕關鍵字")
                }, 8000)
            }
        }

        findViewById<Button>(R.id.btnSnapshot).setOnClickListener {
            toast("已記錄 ${SettingsSnapshot.save(this)} 個設定鍵，現在去按實體關螢幕鍵")
        }
        findViewById<Button>(R.id.btnDiff).setOnClickListener {
            toast(SettingsSnapshot.diff(this))
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            toast("開始掃描：盯著螢幕，一變黑就馬上點一下；沒變黑就不要碰")
            ScreenOff.scanPresetBroadcasts(this) { summary ->
                toast(summary)
                editAction.setText(Prefs.customAction(this))
            }
        }

        findViewById<Button>(R.id.btnDiscover).setOnClickListener {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }

        findViewById<Button>(R.id.btnRestore).setOnClickListener {
            BlackOverlay.hide(applicationContext)
            toast(ScreenOff.restoreSystemSettings(this))
        }
    }

    // === ③ 正式行為設定 ===

    private fun setupSettings() {
        val spinner = findViewById<Spinner>(R.id.spinnerMethod)
        val methods = visibleMethods()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(methods.indexOf(Prefs.method(this)).coerceAtLeast(0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (methods[pos] != Prefs.method(this@DevActivity)) {
                    Prefs.setMethod(this@DevActivity, methods[pos])
                    Logx.d("正式關螢幕方法改為 ${methods[pos].code}")
                }
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        editDelay.setText((Prefs.getDelayMillis(this) / 1000).toString())
        findViewById<Button>(R.id.btnSave).setOnClickListener {
            val sec = editDelay.text.toString().toLongOrNull()
            if (sec == null || sec < 1) toast("請輸入至少 1 秒")
            else {
                Prefs.setDelayMillis(this, sec * 1000)
                Prefs.setCustomAction(this, editAction.text.toString())
                toast("已儲存：$sec 秒")
                Logx.d("延遲改為 $sec 秒")
            }
        }

        val rg = findViewById<RadioGroup>(R.id.rgOtherOp)
        rg.check(if (Prefs.otherOpCancels(this)) R.id.radioCancel else R.id.radioReset)
        rg.setOnCheckedChangeListener { _, id ->
            Prefs.setOtherOpCancels(this, id == R.id.radioCancel)
        }

        val switches = findViewById<LinearLayout>(R.id.containerSwitches)
        addSwitch(switches, "總開關：自動關螢幕", Prefs.enabled(this), retired = true) { Prefs.setEnabled(this, it) }
        addSwitch(switches, "音量變化就開始倒數", Prefs.triggerVolume(this)) { Prefs.setTriggerVolume(this, it) }
        addSwitch(switches, "輪詢音量值（這台不走 AudioManager，白工）", Prefs.pollVolume(this), retired = true) { Prefs.setPollVolume(this, it) }
        addSwitch(switches, "備援：螢幕一亮就倒數（不管原因）", Prefs.triggerScreenOn(this), retired = true) { Prefs.setTriggerScreenOn(this, it) }
        addSwitch(switches, "倒數開始時顯示提示（除錯用）", Prefs.showToast(this), retired = true) { Prefs.setShowToast(this, it) }
        addSwitch(switches, "▼ 顯示已證實無效的所有選項", Prefs.showAllMethods(this)) {
            Prefs.setShowAllMethods(this, it)
            recreate()
        }
        addSwitch(switches, "通知欄常駐開關", Prefs.showNotification(this)) {
            Prefs.setShowNotification(this, it)
            if (it) NotifyToggle.show(this) else NotifyToggle.hide(this)
        }
        addSwitch(switches, "角落顯示狀態圓點（通知欄已經看得到）", Prefs.showStateDot(this), retired = true) {
            Prefs.setShowStateDot(this, it)
            StateDot.refresh(applicationContext, Prefs.enabled(this))
        }
        addSwitch(switches, "音量歸零後再按「−」五次切換（車機收不到訊號）", Prefs.volZeroToggle(this), retired = true) { Prefs.setVolZeroToggle(this, it) }
        addSwitch(switches, "螢幕連點五下切換（不好用）", Prefs.tapToggle(this), retired = true) { Prefs.setTapToggle(this, it) }
        addSwitch(switches, "⚠ 有新畫面跳出時自動撤掉黑幕（只影響方法 J）", Prefs.dropOnNewWindow(this), retired = true) { Prefs.setDropOnNewWindow(this, it) }
        addSwitch(switches, "蓋黑幕時連系統亮度一起壓到 0（實測沒差）", Prefs.dimSystem(this), retired = true) { Prefs.setDimSystem(this, it) }
        addSwitch(switches, "只有「這次螢幕是被音量噴醒的」才關螢幕", Prefs.requireScreenOffFirst(this)) { Prefs.setRequireScreenOffFirst(this, it) }
        addSwitch(switches, "黑幕被點掉後，沒操作就自動再黑（方法 J 專用）", Prefs.autoRedark(this), retired = true) { Prefs.setAutoRedark(this, it) }
        addSwitch(switches, "診斷模式：記錄車機所有動靜（找音量訊號用）", Prefs.diagnostic(this)) { Prefs.setDiagnostic(this, it) }
        addSwitch(switches, "全事件模式：連畫面內容變化／觸控／按鍵放開都記（很吵）", Prefs.logEverything(this)) {
            Prefs.setLogEverything(this, it)
            ScreenGuardService.instance?.refreshEventMask()
        }
        addSwitch(switches, "黑幕開著調音量時重貼黑幕（方法 J 專用）", Prefs.reassertOnVolume(this), retired = true) { Prefs.setReassertOnVolume(this, it) }

        val editVolKeys = findViewById<EditText>(R.id.editVolKeys)
        val editVolWindows = findViewById<EditText>(R.id.editVolWindows)
        val editVolEventPkg = findViewById<EditText>(R.id.editVolEventPkg)
        val editVolEventCls = findViewById<EditText>(R.id.editVolEventCls)
        editVolKeys.setText(Prefs.volumeSettingKeysRaw(this))
        editVolWindows.setText(Prefs.volumeWindowPkgsRaw(this))
        val editOffEventPkg = findViewById<EditText>(R.id.editOffEventPkg)
        val editOffEventCls = findViewById<EditText>(R.id.editOffEventCls)
        // 候選廣播：點一下送一個，不必先填欄位
        val candidates = findViewById<LinearLayout>(R.id.containerCandidates)
        ScreenOff.CANDIDATES.forEach { cand ->
            candidates.addView(Button(this).apply {
                text = cand.label
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener {
                    Logx.d("【手動】送出 ${cand.action} ${cand.extras}")
                    val r = ScreenOff.sendAction(this@DevActivity, cand.action, cand.extras)
                    toast(r.msg)
                }
            })
        }

        val editCarActions = findViewById<EditText>(R.id.editCarActions)
        editCarActions.setText(Prefs.carActionsRaw(this))
        val editLogOnly = findViewById<EditText>(R.id.editLogOnly)
        editLogOnly.setText(Prefs.logOnlyPkgsRaw(this))
        val editOverlayBrightness = findViewById<EditText>(R.id.editOverlayBrightness)
        editOverlayBrightness.setText(Prefs.overlayBrightness(this).toString())
        editVolEventPkg.setText(Prefs.volumeEventPkg(this))
        editVolEventCls.setText(Prefs.volumeEventCls(this))
        editOffEventPkg.setText(Prefs.screenOffEventPkg(this))
        editOffEventCls.setText(Prefs.screenOffEventCls(this))
        findViewById<Button>(R.id.btnSaveAdvanced).setOnClickListener {
            Prefs.setVolumeSettingKeys(this, editVolKeys.text.toString())
            Prefs.setVolumeWindowPkgs(this, editVolWindows.text.toString())
            Prefs.setVolumeEventPkg(this, editVolEventPkg.text.toString().trim())
            Prefs.setVolumeEventCls(this, editVolEventCls.text.toString().trim())
            Prefs.setScreenOffEventPkg(this, editOffEventPkg.text.toString().trim())
            Prefs.setScreenOffEventCls(this, editOffEventCls.text.toString().trim())
            editOverlayBrightness.text.toString().toIntOrNull()?.let { Prefs.setOverlayBrightness(this, it) }
            Prefs.setCarActions(this, editCarActions.text.toString())
            Prefs.setLogOnlyPkgs(this, editLogOnly.text.toString())
            ScreenGuardService.instance?.reloadCarActions()
            Logx.d("進階觸發已更新：音量條=${editVolEventPkg.text}/${editVolEventCls.text} 關閉螢幕鈕=${editOffEventPkg.text}/${editOffEventCls.text} 設定鍵=${editVolKeys.text} 視窗=${editVolWindows.text}")
            toast("已儲存")
            updateStatus()
        }
        findViewById<Button>(R.id.btnVolumeDump).setOnClickListener {
            val svc = ScreenGuardService.instance
            if (svc == null) toast("請先啟用無障礙服務")
            else {
                Logx.d("目前各 stream 音量：${svc.volumeDump()}")
                toast("已寫進記錄，調完音量再按一次比對")
            }
        }

        findViewById<Button>(R.id.btnSimulate).setOnClickListener {
            val svc = ScreenGuardService.instance
            if (svc == null) toast("請先啟用無障礙服務")
            else {
                svc.arm("手動模擬")
                toast("倒數開始，放著別動看會不會關")
            }
        }
    }

    /** @param retired 已證實在這台車機無效，預設不顯示。 */
    private fun addSwitch(
        parent: LinearLayout,
        label: String,
        checked: Boolean,
        retired: Boolean = false,
        onChange: (Boolean) -> Unit
    ) {
        if (retired && !Prefs.showAllMethods(this)) return
        val s = Switch(this).apply {
            text = label
            textSize = 15f
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 8 }
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean ->
                onChange(c)
                Logx.d("設定變更：$label = $c")
            }
        }
        parent.addView(s)
    }

    // === ④ 記錄 ===

    private fun setupLogButtons() {
        findViewById<Button>(R.id.btnRefreshLog).setOnClickListener { refreshLog() }
        findViewById<Button>(R.id.btnClearLog).setOnClickListener {
            Logx.clear()
            refreshLog()
        }
        findViewById<Button>(R.id.btnCopyLog).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("screenguard", Logx.text()))
            toast("記錄已複製到剪貼簿")
        }
    }

    /**
     * 逐行渲染，認得出來的行配一顆執行鈕 ——
     * 看到「TYPE_VIEW_CLICKED pkg=com.ts.mytouch id=btn_screen_off」就能當場試，
     * 不必再手抄 id 去填欄位。
     */
    private fun refreshLog() {
        // 記錄一直在進，每筆都重建幾百個 View 會卡，限流
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastLogRenderAt < 500) return
        lastLogRenderAt = now

        logContainer.removeAllViews()
        val lines = Logx.lines()
        if (lines.isEmpty()) {
            logContainer.addView(TextView(this).apply {
                text = "（尚無記錄）"
                textSize = 12f
            })
            return
        }
        lines.forEach { line -> logContainer.addView(logRow(line)) }
    }

    private fun logRow(line: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(TextView(this).apply {
            text = line
            textSize = 11f
            setTextIsSelectable(true)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        replayFor(line)?.let { (label, act) ->
            row.addView(Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                setPadding(16, 2, 16, 2)
                setOnClickListener { act() }
            })
        }
        return row
    }

    /** 這一行能不能重現？回傳（按鈕文字, 動作），認不出來就 null。 */
    private fun replayFor(line: String): Pair<String, () -> Unit>? {
        // 廣播：只要行中有像 action 的字串就能重送
        Regex("""(?:[a-z][\w]*\.){2,}[A-Za-z_][\w]*""").find(line)?.value
            ?.takeIf { line.contains("廣播") || line.contains("掃描") }
            ?.let { action ->
                return "送它" to {
                    Prefs.setCustomAction(this, action)
                    editAction.setText(action)
                    ScreenOff.run(this, LockMethod.CUSTOM_BROADCAST) { r -> toast("$action：${r.msg}") }
                }
            }

        // 點擊事件：拿 pkg / cls / id 試方法 M
        val pkg = Regex("""pkg=([\w.]+)""").find(line)?.groupValues?.get(1)
        if (pkg.isNullOrEmpty() || pkg == BuildInfo.PKG) return null
        val cls = Regex("""cls=([\w$]+)""").find(line)?.groupValues?.get(1).orEmpty()
        val id = Regex("""id=([\w.]+)""").find(line)?.groupValues?.get(1).orEmpty()
        val key = if (id.isNotEmpty()) id else return null   // 沒 id 就認不出那一顆，不給按鈕

        return "點它" to {
            Prefs.setClickKeys(this, key)
            Prefs.setScreenOffEventPkg(this, pkg)
            if (cls.isNotEmpty()) Prefs.setScreenOffEventCls(this, cls)
            Logx.d("【記錄重現】試點 $pkg / $cls / id=$key")
            ScreenOff.run(this, LockMethod.CLICK_CAR_BUTTON) { r ->
                toast(
                    if (r.ok) "點下去了：${r.msg}"
                    else "點不到：${r.msg}（輔助球要先展開，它才在畫面上）"
                )
            }
        }
    }

    private fun updateStatus() {
        findViewById<TextView>(R.id.officialText).text = if (Prefs.isOfficialProfile(this)) {
            "目前：正式方案運作中 ✅\n" +
                "偵測 ${Prefs.volumeEventPkg(this)} 的 ${Prefs.volumeEventCls(this)} 音量條 → " +
                "${Prefs.getDelayMillis(this) / 1000} 秒沒操作 → 蓋黑幕。可以關掉 App 了。"
        } else {
            "按下去＝方法 J 黑幕 + 只認車機音量條 + ${Prefs.getDelayMillis(this) / 1000} 秒 + 其他操作取消，" +
                "之後關掉 App 就會在背景運作。"
        }

        val acc = isAccessibilityEnabled()
        val admin = runCatching { dpm.isAdminActive(adminComponent) }.getOrDefault(false)
        val overlay = ScreenOff.canDrawOverlay(this)
        val write = ScreenOff.canWriteSettings(this)
        val armed = ScreenGuardService.instance?.isArmed() == true
        statusText.text = buildString {
            append(if (acc) "無障礙服務 ✅" else "無障礙服務 ❌（一定要開，不然什麼都偵測不到）")
            append("\n裝置管理員 ").append(if (admin) "✅" else "❌")
            append("　懸浮視窗 ").append(if (overlay) "✅" else "❌")
            append("　寫入設定 ").append(if (write) "✅" else "❌")
            append("\n目前方法：").append(Prefs.method(this@DevActivity).toString())
            append("　倒數中：").append(if (armed) "是" else "否")
            append("　螢幕：").append(ScreenGuardService.instance?.screenStateText() ?: "未知")
            append("　暗模式：").append(if (ScreenGuardService.instance?.isDarkMode() == true) "是" else "否")
            append("\nAndroid ").append(Build.VERSION.RELEASE)
            append("（API ").append(Build.VERSION.SDK_INT).append("）")
            append(" ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ScreenGuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun startActivityCatch(intent: Intent, fallbackMsg: String) {
        runCatching { startActivity(intent) }.onFailure { toast(fallbackMsg) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
