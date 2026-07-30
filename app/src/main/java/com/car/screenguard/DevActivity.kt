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
    private lateinit var logText: TextView
    private lateinit var editDelay: EditText
    private lateinit var editAction: EditText

    private val adminComponent by lazy { ComponentName(this, AdminReceiver::class.java) }
    private val dpm by lazy { getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        editDelay = findViewById(R.id.editDelay)
        editAction = findViewById(R.id.editAction)

        setupOfficialButton()
        setupPermissionButtons()
        setupTestButtons()
        setupSettings()
        setupLogButtons()
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
                "=== 已套用正式方案：方法 J 黑幕 ＋ 音量條 ${Prefs.volumeEventPkg(this)}/${Prefs.volumeEventCls(this)} " +
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

    // === ② 方法測試 ===

    private fun setupTestButtons() {
        val container = findViewById<LinearLayout>(R.id.containerTests)
        LockMethod.values().forEach { method ->
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

        findViewById<Button>(R.id.btnDump).setOnClickListener {
            Prefs.setClickKeys(this, editClickKeys.text.toString())
            val svc = ScreenGuardService.instance
            if (svc == null) {
                toast("請先啟用無障礙服務")
            } else {
                toast("5 秒後傾印，請馬上切到車機那個有關閉螢幕按鈕的畫面")
                android.os.Handler(mainLooper).postDelayed({
                    val n = svc.dumpNodes()
                    Logx.d("傾印完成，$n 個節點；回 App 看記錄找那顆按鈕")
                }, 5000)
            }
        }

        findViewById<Button>(R.id.btnSnapshot).setOnClickListener {
            toast("已記錄 ${SettingsSnapshot.save(this)} 個設定鍵，現在去按實體關螢幕鍵")
        }
        findViewById<Button>(R.id.btnDiff).setOnClickListener {
            toast(SettingsSnapshot.diff(this))
        }

        findViewById<Button>(R.id.btnScan).setOnClickListener {
            toast("開始掃描，過程中請不要碰螢幕")
            ScreenOff.scanPresetBroadcasts(this) { summary -> toast(summary) }
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
        val methods = LockMethod.values().toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, methods).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(methods.indexOf(Prefs.method(this)))
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
        addSwitch(switches, "總開關：自動關螢幕", Prefs.enabled(this)) { Prefs.setEnabled(this, it) }
        addSwitch(switches, "音量變化就開始倒數", Prefs.triggerVolume(this)) { Prefs.setTriggerVolume(this, it) }
        addSwitch(switches, "輪詢音量值（車機不送廣播時用）", Prefs.pollVolume(this)) { Prefs.setPollVolume(this, it) }
        addSwitch(switches, "備援：螢幕一亮就倒數（不管原因）", Prefs.triggerScreenOn(this)) { Prefs.setTriggerScreenOn(this, it) }
        addSwitch(switches, "倒數開始時顯示提示（除錯用）", Prefs.showToast(this)) { Prefs.setShowToast(this, it) }
        addSwitch(switches, "蓋黑幕時連系統亮度一起壓到 0", Prefs.dimSystem(this)) { Prefs.setDimSystem(this, it) }
        addSwitch(switches, "只有調音量前螢幕是關閉的才變黑", Prefs.requireScreenOffFirst(this)) { Prefs.setRequireScreenOffFirst(this, it) }
        addSwitch(switches, "黑幕被點掉後，沒操作就自動再黑", Prefs.autoRedark(this)) { Prefs.setAutoRedark(this, it) }
        addSwitch(switches, "診斷模式：記錄車機所有動靜（找音量訊號用）", Prefs.diagnostic(this)) { Prefs.setDiagnostic(this, it) }
        addSwitch(switches, "黑幕開著調音量時重貼黑幕蓋掉音量條（會閃一下）", Prefs.reassertOnVolume(this)) { Prefs.setReassertOnVolume(this, it) }

        val editVolKeys = findViewById<EditText>(R.id.editVolKeys)
        val editVolWindows = findViewById<EditText>(R.id.editVolWindows)
        val editVolEventPkg = findViewById<EditText>(R.id.editVolEventPkg)
        val editVolEventCls = findViewById<EditText>(R.id.editVolEventCls)
        editVolKeys.setText(Prefs.volumeSettingKeysRaw(this))
        editVolWindows.setText(Prefs.volumeWindowPkgsRaw(this))
        val editOffEventPkg = findViewById<EditText>(R.id.editOffEventPkg)
        val editOffEventCls = findViewById<EditText>(R.id.editOffEventCls)
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

    private fun addSwitch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
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

    private fun refreshLog() {
        logText.text = Logx.text()
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
