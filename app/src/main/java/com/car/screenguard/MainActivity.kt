package com.car.screenguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast

/**
 * 開車時要用的主畫面：全黑、上半部兩顆大按鈕、下半部才是設定與工具。
 * 詳細設定、12 種關螢幕方法與事件記錄都在 [DevActivity]。
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var editSec: EditText
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        setContentView(R.layout.activity_simple)

        status = findViewById(R.id.statusSimple)
        editSec = findViewById(R.id.editSec)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        editSec.setText((Prefs.getDelayMillis(this) / 1000).toString())

        btnStart.setOnClickListener { start() }
        btnStop.setOnClickListener {
            NotifyToggle.applyEnabled(this, false, "主畫面按下停止")
            toast("已停止")
            updateStatus()
        }

        findViewById<Button>(R.id.btnRecordTaps).setOnClickListener {
            val svc = ScreenGuardService.instance
            if (svc == null) {
                toast("請先啟用無障礙服務")
                return@setOnClickListener
            }
            toast("待會畫面會蓋一層半透明，照上面的指示點兩下")
            moveTaskToBack(true)   // 退到背景，點到的才是車機畫面
            Handler(mainLooper).postDelayed({
                TapRecorder.start(applicationContext, svc) { msg -> Logx.d("側錄結果：$msg") }
            }, 700)
        }

        findViewById<Button>(R.id.btnTestTaps).setOnClickListener {
            if (!Prefs.tapsRecorded(this)) {
                toast("還沒側錄，請先按左邊那顆")
                return@setOnClickListener
            }
            moveTaskToBack(true)
            Handler(mainLooper).postDelayed({
                ScreenOff.run(this, LockMethod.SIMULATE_TAP) { }
            }, 700)
        }

        findViewById<Button>(R.id.btnDev).setOnClickListener {
            startActivity(Intent(this, DevActivity::class.java))
        }

        styleSecondary(findViewById(R.id.btnRecordTaps))
        styleSecondary(findViewById(R.id.btnTestTaps))
        styleSecondary(findViewById(R.id.btnDev))
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
            toast("請在清單裡找到「車機螢幕守衛」並開啟，然後回到這裡再按一次啟用")
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            return
        }
        if (!ScreenOff.canDrawOverlay(this)) {
            toast("請允許「顯示在其他應用程式上層」，然後回到這裡再按一次啟用")
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
        Logx.d("=== 啟用：$sec 秒、方法 ${Prefs.method(this).code} ===")
        NotifyToggle.applyEnabled(this, true, "主畫面按下啟用")
        toast("已啟用，可以關掉 App 了")
        updateStatus()
    }

    // === 外觀 ===

    /** 未選中：黑底＋灰框。 */
    private fun border(color: Int, width: Int = 6) = GradientDrawable().apply {
        setColor(Color.BLACK)
        setStroke(width, color)
        cornerRadius = 18f
    }

    /** 目前狀態那顆：填滿深色底，配黑字，開車時一眼就分得出來。 */
    private fun filled(bg: Int) = GradientDrawable().apply {
        setColor(bg)
        setStroke(6, bg)
        cornerRadius = 18f
    }

    private fun styleSecondary(b: Button) {
        b.background = border(GRAY, 3)
        b.setTextColor(GRAY_TEXT)
        b.isAllCaps = false
    }

    private fun updateStatus() {
        val acc = isAccessibilityEnabled()
        val overlay = ScreenOff.canDrawOverlay(this)
        val on = Prefs.enabled(this)

        // 目前狀態那顆填滿深色＋黑字，另一顆只留灰框
        btnStart.background = if (on) filled(GREEN_DARK) else border(GRAY)
        btnStart.setTextColor(if (on) Color.BLACK else GRAY_TEXT)
        btnStop.background = if (on) border(GRAY) else filled(RED_DARK)
        btnStop.setTextColor(if (on) GRAY_TEXT else Color.BLACK)

        val sec = Prefs.getDelayMillis(this) / 1000
        status.text = when {
            !acc -> "❌ 尚未啟用無障礙服務，按「啟用」我帶你去開"
            !overlay -> "❌ 尚未允許顯示在其他 App 上層，按「啟用」我帶你去開"
            else -> buildString {
                append(if (on) "運作中：" else "已停止：")
                append("調整音量後 $sec 秒沒操作就關螢幕　方式 ")
                append(
                    if (Prefs.method(this@MainActivity) == LockMethod.SIMULATE_TAP)
                        "模擬點擊" else "黑幕（尚未側錄）"
                )
            }
        }
        status.setTextColor(if (!acc || !overlay) RED else GRAY_TEXT)
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, ScreenGuardService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private companion object {
        // 深色底配黑字：夜間不刺眼，但飽和度夠、開車時掃一眼就分得出來
        const val GREEN_DARK = 0xFF2E7D32.toInt()
        const val RED_DARK = 0xFFC62828.toInt()
        const val RED = 0xFFFF5252.toInt()      // 狀態列的警告字
        const val GRAY = 0xFF424242.toInt()
        const val GRAY_TEXT = 0xFF9E9E9E.toInt()
    }
}
