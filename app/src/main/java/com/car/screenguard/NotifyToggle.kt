package com.car.screenguard

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 通知欄的開關。
 *
 * 音量手勢在車機上收不到訊號（音量到 0 之後 MCU 就不再送任何事件），
 * 點螢幕又不好用。通知欄不依賴任何偵測，下滑就能按，是最可靠的一條路。
 */
object NotifyToggle {

    const val ACTION_ENABLE = "com.car.screenguard.action.ENABLE"
    const val ACTION_DISABLE = "com.car.screenguard.action.DISABLE"

    private const val CHANNEL_ID = "screenguard_toggle"
    private const val NOTIFY_ID = 1001

    /** 依目前狀態畫出（或更新）通知。 */
    fun show(c: Context) {
        val app = c.applicationContext
        if (!Prefs.showNotification(app)) {
            hide(app)
            return
        }
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel(nm)

        val enabled = Prefs.enabled(app)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(app, CHANNEL_ID)
        else
            @Suppress("DEPRECATION") Notification.Builder(app)

        val n = builder
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle(if (enabled) "自動關螢幕：開啟中" else "自動關螢幕：已關閉")
            .setContentText(
                if (enabled) "調整音量後沒操作就會關螢幕"
                else "螢幕會一直亮著（適合看導航）"
            )
            .setOngoing(true)          // 不能滑掉，隨時都在
            .setShowWhen(false)
            .setContentIntent(activityIntent(app))
            .addAction(action(app, ACTION_ENABLE, "開啟"))
            .addAction(action(app, ACTION_DISABLE, "關閉"))
            .also { if (Build.VERSION.SDK_INT >= 21) it.setVisibility(Notification.VISIBILITY_PUBLIC) }
            .build()

        runCatching { nm.notify(NOTIFY_ID, n) }
            .onFailure { Logx.d("通知欄開關顯示失敗：${it.message}") }
    }

    fun hide(c: Context) {
        val nm = c.applicationContext
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.cancel(NOTIFY_ID) }
    }

    /** 通知欄按鈕、主畫面、手勢都走這裡，狀態才不會各走各的。 */
    fun applyEnabled(c: Context, enabled: Boolean, how: String) {
        val app = c.applicationContext
        Prefs.setEnabled(app, enabled)
        if (!enabled) {
            ScreenGuardService.instance?.cancel(how)
            BlackOverlay.hide(app, notify = false)
        }
        StateDot.refresh(app, enabled)
        show(app)
        Logx.d("=== $how -> ${if (enabled) "開啟" else "關閉"}自動關螢幕 ===")
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < 26) return
        val ch = NotificationChannel(
            CHANNEL_ID,
            "自動關螢幕開關",
            NotificationManager.IMPORTANCE_LOW   // 不出聲、不跳橫幅
        ).apply {
            description = "常駐在通知欄的開關"
            setShowBadge(false)
        }
        runCatching { nm.createNotificationChannel(ch) }
    }

    private fun action(c: Context, action: String, label: String): Notification.Action {
        val pi = PendingIntent.getBroadcast(
            c,
            action.hashCode(),
            Intent(c, ToggleReceiver::class.java).setAction(action),
            pendingFlags()
        )
        return if (Build.VERSION.SDK_INT >= 23)
            Notification.Action.Builder(null as android.graphics.drawable.Icon?, label, pi).build()
        else
            @Suppress("DEPRECATION") Notification.Action.Builder(0, label, pi).build()
    }

    private fun activityIntent(c: Context): PendingIntent = PendingIntent.getActivity(
        c, 0,
        Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        pendingFlags()
    )

    private fun pendingFlags(): Int =
        PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0
}
