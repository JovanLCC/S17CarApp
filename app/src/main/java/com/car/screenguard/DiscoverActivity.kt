package com.car.screenguard

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 車機 ROM 通常自己就有「關螢幕」的元件（原廠設定裡的省電/黑屏功能）。
 * 這頁用關鍵字把它們找出來，直接對元件發廣播或啟動，看哪個會關螢幕。
 */
class DiscoverActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var container: LinearLayout

    private data class Item(val kind: String, val cn: ComponentName, val exported: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Logx.init(this)
        setContentView(R.layout.activity_discover)

        status = findViewById(R.id.scanStatus)
        container = findViewById(R.id.containerResults)
        val keywords = findViewById<android.widget.EditText>(R.id.editKeywords)
        keywords.setText("screen,backlight,lcd,sleep,black,blank,display")

        findViewById<Button>(R.id.btnScanComponents).setOnClickListener {
            scan(keywords.text.toString().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() })
        }
    }

    private fun scan(keys: List<String>) {
        container.removeAllViews()
        status.text = "掃描中…"
        thread {
            val pm = packageManager
            val found = mutableListOf<Item>()
            val pkgs = runCatching { pm.getInstalledPackages(0) }.getOrDefault(emptyList())
            for (p in pkgs) {
                val info = runCatching {
                    pm.getPackageInfo(
                        p.packageName,
                        PackageManager.GET_ACTIVITIES or PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES
                    )
                }.getOrNull() ?: continue

                fun collect(kind: String, names: Array<out android.content.pm.ComponentInfo>?) {
                    names?.forEach { ci ->
                        val n = ci.name.lowercase()
                        if (keys.any { n.contains(it) } && !ci.name.startsWith(BuildInfo.PKG)) {
                            found.add(Item(kind, ComponentName(ci.packageName, ci.name), ci.exported))
                        }
                    }
                }
                collect("Activity", info.activities)
                collect("Receiver", info.receivers)
                collect("Service", info.services)
            }
            main.post { render(found) }
        }
    }

    private fun render(found: List<Item>) {
        status.text = "找到 ${found.size} 個元件（打勾＝exported，只有 exported 的才叫得動）"
        Logx.d("元件掃描完成，找到 ${found.size} 個")
        found.sortedBy { it.cn.packageName }.forEach { item ->
            val b = Button(this).apply {
                isAllCaps = false
                textSize = 12f
                text = "${if (item.exported) "✔" else "✖"} ${item.kind}｜${item.cn.packageName}\n${item.cn.className.substringAfterLast('.')}"
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { fire(item) }
            }
            container.addView(b)
        }
    }

    private fun fire(item: Item) {
        val intent = Intent().setComponent(item.cn)
        val r = runCatching {
            when (item.kind) {
                "Receiver" -> { sendBroadcast(intent); "已發廣播" }
                "Service" -> { startService(intent); "已啟動服務" }
                else -> { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); "已啟動畫面" }
            }
        }.getOrElse { "失敗：${it.javaClass.simpleName} ${it.message}" }
        Logx.d("嘗試元件 ${item.cn.flattenToShortString()} -> $r")
        Toast.makeText(this, "${item.cn.className.substringAfterLast('.')}：$r", Toast.LENGTH_SHORT).show()
    }
}
