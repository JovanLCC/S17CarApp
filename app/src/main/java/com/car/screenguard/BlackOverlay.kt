package com.car.screenguard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 保底方案：真的關不掉螢幕時，用一層全黑視窗蓋住並把「視窗亮度」壓到最低。
 * 視窗亮度不需要「修改系統設定」權限，只要有懸浮視窗權限即可，
 * 效果接近關螢幕（背光仍微亮）。點一下畫面即可解除。
 */
object BlackOverlay {

    private var view: View? = null

    /** 黑幕被使用者點掉時通知服務（用來排「自動再黑」、離開暗模式）。 */
    @Volatile
    var onDismissed: (() -> Unit)? = null

    /** 黑幕蓋上時通知服務（代表進入暗模式）。 */
    @Volatile
    var onShown: (() -> Unit)? = null

    fun isShowing() = view != null

    /**
     * 車機自己的音量條／畫面若疊到黑幕上面，重貼一次把黑幕拉回最上層。
     * 只有原本就在顯示時才動作。
     */
    fun reassert(app: Context) {
        if (view == null) return
        hide(app, notify = false)
        show(app)
    }

    /** 必須在主執行緒呼叫。 */
    fun show(app: Context): LockResult {
        if (view != null) return LockResult(true, "覆蓋層已經在顯示中")
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 0f = BRIGHTNESS_OVERRIDE_OFF，語意是關背光；有些機器真的會關，有些只壓到最低
            screenBrightness = Prefs.overlayBrightness(app) / 1000f
            dimAmount = 1f
        }

        val v = View(app).apply {
            setBackgroundColor(Color.BLACK)
            @Suppress("DEPRECATION")
            systemUiVisibility = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            setOnTouchListener { _, _ ->
                Logx.d("覆蓋層被點擊 -> 解除假關螢幕")
                hide(app)
                true
            }
        }

        return runCatching {
            wm.addView(v, lp)
            view = v
            Logx.d("已顯示全黑覆蓋層（視窗亮度 ${Prefs.overlayBrightness(app)}‰，點畫面解除）")
            // LCD 的背光不會因為畫面全黑而關掉，再把系統亮度一起壓到 0 才夠暗
            if (Prefs.dimSystem(app)) {
                val r = ScreenOff.brightnessZero(app)
                Logx.d("同步壓低系統亮度：${r.msg}")
            }
            onShown?.invoke()
            LockResult(true, "已顯示全黑覆蓋層，點畫面任一處解除")
        }.getOrElse {
            LockResult(false, "覆蓋層顯示失敗：${it.javaClass.simpleName} ${it.message}")
        }
    }

    @JvmOverloads
    fun hide(app: Context, notify: Boolean = true) {
        val v = view ?: return
        view = null
        runCatching {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
        // 重貼（notify=false）時不還原亮度，不然會閃一下亮的
        if (notify) {
            ScreenOff.restoreSystemSettings(app)
            onDismissed?.invoke()
        }
    }
}
