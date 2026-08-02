package com.car.screenguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 一般使用的主畫面：設定秒數、按開始使用，就這樣。
 * 12 種關螢幕方法、掃描工具、細項設定與事件記錄都在 [DevActivity]。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var editSec: EditText
    private lateinit var checkRequireOff: CheckBox
    private lateinit var checkDimSystem: CheckBox

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        setContentView(R.layout.activity_simple)

        status = findViewById(R.id.statusSimple)
        editSec = findViewById(R.id.editSec)
        editSec.setText((Prefs.getDelayMillis(this) / 1000).toString())

        findViewById<Button>(R.id.btnRecordTaps).setOnClickListener {
            val svc = ScreenGuardService.instance
            if (svc == null) {
                toast("請先啟用無障礙服務")
                return@setOnClickListener
            }
            toast("待會畫面會蓋一層半透明，照上面的指示點兩下")
            // 讓 App 退到背景，這樣點的才是車機畫面
            moveTaskToBack(true)
            android.os.Handler(mainLooper).postDelayed({
                TapRecorder.start(applicationContext, svc) { msg ->
                    Logx.d("側錄結果：$msg")
                }
            }, 700)
        }

        findViewById<Button>(R.id.btnTestTaps).setOnClickListener {
            if (!Prefs.tapsRecorded(this)) {
                toast("還沒側錄，請先按上面那顆")
                return@setOnClickListener
            }
            moveTaskToBack(true)
            android.os.Handler(mainLooper).postDelayed({
                ScreenOff.run(this, LockMethod.SIMULATE_TAP) { }
            }, 700)
        }

        checkDimSystem = findViewById(R.id.checkDimSystem)
        checkDimSystem.isChecked = Prefs.dimSystem(this)
        checkDimSystem.setOnCheckedChangeListener { _, c ->
            Prefs.setDimSystem(this, c)
            Logx.d("設定變更：連系統亮度一起壓到 0 = $c")
            if (c && !ScreenOff.canWriteSettings(this)) {
                toast("需要「修改系統設定」權限，我帶你去開")
                if (Build.VERSION.SDK_INT >= 23) {
                    runCatching {
                        startActivity(
                            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                        )
                    }
                }
            }
        }

        checkRequireOff = findViewById(R.id.checkRequireOff)
        checkRequireOff.isChecked = Prefs.requireScreenOffFirst(this)
        checkRequireOff.setOnCheckedChangeListener { _, c ->
            Prefs.setRequireScreenOffFirst(this, c)
            Logx.d("設定變更：必須先按過關閉螢幕 = $c")
            updateStatus()
        }

        findViewById<Button>(R.id.btnStart).setOnClickListener { start() }

        findViewById<Button>(R.id.btnDarkNow).setOnClickListener {
            if (!ScreenOff.canDrawOverlay(this)) {
                toast("需要「顯示在其他應用程式上層」權限才能變黑")
                return@setOnClickListener
            }
            Logx.d("【手動】使用者按下立刻變黑")
            ScreenOff.run(this, LockMethod.BLACK_OVERLAY) { r ->
                if (!r.ok) toast("變黑失敗：${r.msg}")
            }
        }

        findViewById<Button>(R.id.btnStop).setOnClickListener {
            Prefs.setEnabled(this, false)
            ScreenGuardService.instance?.cancel("使用者按停止")
            BlackOverlay.hide(applicationContext)
            StateDot.refresh(applicationContext, false)
            Logx.d("=== 使用者按下停止 ===")
            toast("已停止")
            updateStatus()
        }

        findViewById<Button>(R.id.btnDev).setOnClickListener {
            startActivity(Intent(this, DevActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // 自我修復：萬一 App 在壓低亮度時被系統殺掉，回到這裡就把亮度還原，不會一直暗著
        if (!BlackOverlay.isShowing() && ScreenOff.hasPendingRestore(this)) {
            Logx.d("偵測到亮度沒還原（可能上次被中斷）-> 自動還原")
            ScreenOff.restoreSystemSettings(this)
        }
        updateStatus()
    }

    private fun start() {
        val sec = editSec.text.toString().toLongOrNull()
        if (sec == null || sec < 1) {
            toast("請輸入至少 1 秒")
            return
        }

        // 缺哪個權限就直接把使用者帶到那一頁，不要只丟訊息
        if (!isAccessibilityEnabled()) {
            toast("請在清單裡找到「車機螢幕守衛」並開啟，然後回到這裡再按一次開始使用")
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        if (!ScreenOff.canDrawOverlay(this)) {
            toast("請允許「顯示在其他應用程式上層」，然後回到這裡再按一次開始使用")
            if (Build.VERSION.SDK_INT >= 23) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
            }
            return
        }

        Prefs.setDelayMillis(this, sec * 1000)
        Prefs.applyOfficialProfile(this)
        // 正式方案會把這項設成預設值，所以在後面照使用者勾選的狀態覆寫回去
        Prefs.setRequireScreenOffFirst(this, checkRequireOff.isChecked)
        Logx.d("=== 開始使用：$sec 秒、方法 ${Prefs.method(this).code}、音量條 ${Prefs.volumeEventPkg(this)}/${Prefs.volumeEventCls(this)} ===")
        StateDot.refresh(applicationContext, true)

        // 「更黑」要改系統亮度，缺權限的話順手帶去開，不擋住啟用流程
        if (Prefs.dimSystem(this) && !ScreenOff.canWriteSettings(this)) {
            toast("已開始使用。「更黑」還需要「修改系統設定」權限，我帶你去開")
            if (Build.VERSION.SDK_INT >= 23) {
                runCatching {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                    )
                }
            }
        } else {
            toast("已開始使用，可以關掉 App 了")
        }
        updateStatus()
    }

    private fun updateStatus() {
        val acc = isAccessibilityEnabled()
        val overlay = ScreenOff.canDrawOverlay(this)
        val on = Prefs.enabled(this)
        val sec = Prefs.getDelayMillis(this) / 1000
        val gate = Prefs.requireScreenOffFirst(this)
        val screenState = ScreenGuardService.instance?.screenStateText() ?: "未知"
        status.text = when {
            !acc -> "❌ 尚未啟用無障礙服務\n按「開始使用」我會帶你去開"
            !overlay -> "❌ 尚未允許顯示在其他 App 上層\n按「開始使用」我會帶你去開"
            !on -> "⏸ 已停止\n按「開始使用」重新啟用"
            else -> buildString {
                append("✅ 運作中\n")
                append(if (gate) "螢幕原本關閉、被音量喚醒後" else "調整音量後")
                append(" $sec 秒沒有其他操作就關螢幕。\n")
                append("方式：")
                if (Prefs.method(this@MainActivity) == LockMethod.SIMULATE_TAP) {
                    val (x1, y1) = Prefs.tap1(this@MainActivity)
                    val (x2, y2) = Prefs.tap2(this@MainActivity)
                    append("模擬點擊 ($x1,$y1) → ($x2,$y2)")
                } else {
                    append("黑幕（尚未側錄點擊位置）")
                }
                append("\n\nAndroid 看到的螢幕狀態：").append(screenState)
            }
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ScreenGuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
