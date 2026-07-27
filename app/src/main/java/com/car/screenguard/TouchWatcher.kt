package com.car.screenguard

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager

/**
 * 偵測「使用者有沒有碰螢幕」。
 *
 * 無障礙事件只有在元件自己回報時才會發（點按鈕、捲動清單），
 * 在車機桌面上單純滑一下手指是收不到任何事件的。
 * 所以掛一個 1×1、不吃觸控的隱形視窗並開 FLAG_WATCH_OUTSIDE_TOUCH，
 * 使用者碰到視窗以外的任何地方（＝整個螢幕）就會收到 ACTION_OUTSIDE。
 *
 * 只在倒數期間掛著，倒數結束或取消就拿掉。
 */
object TouchWatcher {

    private var view: View? = null

    @Volatile
    var onTouch: (() -> Unit)? = null

    fun isRunning() = view != null

    /** 必須在主執行緒呼叫。 */
    fun start(app: Context) {
        if (view != null) return
        if (!ScreenOff.canDrawOverlay(app)) {
            Logx.d("觸控偵測無法啟動：沒有懸浮視窗權限")
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val lp = WindowManager.LayoutParams(
            1, 1, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or   // 不擋使用者操作
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = View(app).apply {
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_OUTSIDE) onTouch?.invoke()
                false
            }
        }
        runCatching {
            wm.addView(v, lp)
            view = v
        }.onFailure { Logx.d("觸控偵測啟動失敗：${it.message}") }
    }

    fun stop(app: Context) {
        val v = view ?: return
        view = null
        runCatching {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }
}
