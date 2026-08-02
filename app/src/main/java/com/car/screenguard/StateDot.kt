package com.car.screenguard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 角落的狀態圓點：綠＝自動關螢幕開啟中，紅＝已關閉。
 *
 * Toast 只出現幾秒，錯過就不知道現在是哪個狀態；這顆點隨時看得到。
 * 不吃觸控，所以不影響任何操作。
 */
object StateDot {

    private var view: View? = null
    private var lastEnabled: Boolean? = null

    private const val GREEN = 0xFF00C853.toInt()
    private const val RED = 0xFFD50000.toInt()

    /** 依設定顯示／隱藏，並把顏色更新成目前狀態。 */
    fun refresh(app: Context, enabled: Boolean) {
        if (!Prefs.showStateDot(app) || !ScreenOff.canDrawOverlay(app)) {
            hide(app)
            return
        }
        if (view == null) show(app)
        if (lastEnabled != enabled) {
            lastEnabled = enabled
            view?.background = dot(enabled)
        }
    }

    private fun dot(enabled: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (enabled) GREEN else RED)
        // 深色描邊，免得落在同色背景上看不見
        setStroke(2, Color.argb(160, 0, 0, 0))
    }

    private fun show(app: Context) {
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val size = (app.resources.displayMetrics.density * 14).toInt().coerceAtLeast(10)
        val margin = (app.resources.displayMetrics.density * 6).toInt()

        val lp = WindowManager.LayoutParams(
            size, size, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or   // 不吃觸控
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = margin
            y = margin
            alpha = 0.85f
        }

        val v = View(app)
        runCatching {
            wm.addView(v, lp)
            view = v
            lastEnabled = null
        }.onFailure { Logx.d("狀態圓點顯示失敗：${it.message}") }
    }

    fun hide(app: Context) {
        val v = view ?: return
        view = null
        lastEnabled = null
        runCatching {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }
}
