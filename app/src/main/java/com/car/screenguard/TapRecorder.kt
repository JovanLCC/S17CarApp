package com.car.screenguard

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 側錄兩個點擊位置。
 *
 * 車機左邊那顆懸浮輔助球要點兩下才會關螢幕：第一下展開選單，第二下才是關螢幕圖示。
 * 所以錄製也分兩段 —— 錄完第一個位置後，App 會**先幫使用者按下去把選單展開**，
 * 再讓他點第二個位置，不然選單沒開根本看不到圖示在哪。
 *
 * 蓋在上面的收錄層是半透明的，看得到底下的選單；它會吃掉這一下觸控（我們只要座標）。
 */
object TapRecorder {

    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null

    fun isRecording() = view != null

    /**
     * @param onFinish 錄完（或失敗）時回報訊息
     */
    fun start(app: Context, svc: ScreenGuardService, onFinish: (String) -> Unit) {
        if (view != null) {
            onFinish("已經在側錄中")
            return
        }
        if (!ScreenOff.canDrawOverlay(app)) {
            onFinish("需要「顯示在其他應用程式上層」權限才能側錄")
            return
        }

        show(app, "第 1 步：點一下車機的輔助按鈕（那顆浮動球）") { x1, y1 ->
            Prefs.setTap1(app, x1, y1)
            Logx.d("側錄第 1 點：($x1,$y1)")
            hide(app)

            // 先把收錄層拿掉再幫他按，不然這一下會被自己吃掉
            main.postDelayed({
                svc.tap(x1, y1)
                main.postDelayed({
                    show(app, "第 2 步：選單展開了，點一下「關閉螢幕」那個圖示") { x2, y2 ->
                        Prefs.setTap2(app, x2, y2)
                        Logx.d("側錄第 2 點：($x2,$y2)")
                        hide(app)
                        Prefs.setMethod(app, LockMethod.SIMULATE_TAP)
                        Logx.d("=== 側錄完成，關螢幕方法已切換成 N 模擬點擊 ===")
                        onFinish("側錄完成：($x1,$y1) → ($x2,$y2)，方法已改為模擬點擊")
                    }
                }, Prefs.tapGap(app))
            }, 250)
        }
    }

    fun cancel(app: Context) {
        hide(app)
    }

    private fun show(app: Context, hint: String, onTap: (Int, Int) -> Unit) {
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
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START }

        val root = FrameLayout(app).apply {
            setBackgroundColor(0x33000000)   // 半透明，看得到底下的選單
            addView(TextView(app).apply {
                text = hint
                textSize = 18f
                setTextColor(Color.WHITE)
                setBackgroundColor(0xCC000000.toInt())
                setPadding(24, 16, 24, 16)
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.END
                )
            })
            setOnTouchListener { _, e ->
                if (e.action == MotionEvent.ACTION_DOWN) {
                    onTap(e.rawX.toInt(), e.rawY.toInt())
                }
                true
            }
        }

        runCatching {
            wm.addView(root, lp)
            view = root
        }.onFailure { Logx.d("側錄層顯示失敗：${it.message}") }
    }

    private fun hide(app: Context) {
        val v = view ?: return
        view = null
        runCatching {
            (app.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v)
        }
    }
}
