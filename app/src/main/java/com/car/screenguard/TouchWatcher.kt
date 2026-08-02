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
    private var blocker: View? = null

    @Volatile
    var onTouch: (() -> Unit)? = null

    fun isRunning() = view != null

    /** 吃掉觸控的那層是不是開著（開著時偵測層的事件要忽略，否則同一下會算兩次）。 */
    fun isBlocking() = blocker != null

    /**
     * 蓋一層全螢幕、會**吃掉**觸控的透明層。
     *
     * 連點切換走到一半時用：前幾下攔不住（偵測層是 NOT_TOUCHABLE，攔了車機就不能操作），
     * 但從這裡開始的點擊就不會再穿透到底下的車機 UI。
     */
    fun startBlocking(app: Context) {
        if (blocker != null) return
        if (!ScreenOff.canDrawOverlay(app)) return
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = View(app).apply {
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) onTouch?.invoke()
                true // 吃掉，不往下傳
            }
        }
        runCatching {
            wm.addView(v, lp)
            blocker = v
        }
    }

    fun stopBlocking(app: Context) {
        val v = blocker ?: return
        blocker = null
        runCatching {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }

    private fun overlayType() = if (Build.VERSION.SDK_INT >= 26)
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    else
        @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

    /** 必須在主執行緒呼叫。 */
    fun start(app: Context) {
        if (view != null) return
        if (!ScreenOff.canDrawOverlay(app)) {
            Logx.d("觸控偵測無法啟動：沒有懸浮視窗權限")
            return
        }
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val lp = WindowManager.LayoutParams(
            1, 1, overlayType(),
            // 這裡千萬不能用 FLAG_NOT_TOUCHABLE：那會讓視窗從輸入系統整個消失，
            // 連 ACTION_OUTSIDE 都收不到。正確組合是 NOT_TOUCH_MODAL + WATCH_OUTSIDE_TOUCH：
            // 視窗只有 1×1 像素、擋不到什麼，但碰到它以外的地方就會收到 ACTION_OUTSIDE。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val v = View(app).apply {
            setOnTouchListener { _, e ->
                // 碰到別的地方＝ACTION_OUTSIDE；碰到這 1 像素本身＝ACTION_DOWN，兩種都算
                if (e.action == MotionEvent.ACTION_OUTSIDE || e.action == MotionEvent.ACTION_DOWN) {
                    onTouch?.invoke()
                }
                false
            }
        }
        runCatching {
            wm.addView(v, lp)
            view = v
            Logx.d("觸控偵測已啟動")
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
