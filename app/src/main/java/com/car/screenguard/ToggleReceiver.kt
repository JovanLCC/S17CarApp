package com.car.screenguard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 接通知欄那兩顆按鈕。 */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val c = context ?: return
        Logx.init(c)
        when (intent?.action) {
            NotifyToggle.ACTION_ENABLE -> NotifyToggle.applyEnabled(c, true, "通知欄按下開啟")
            NotifyToggle.ACTION_DISABLE -> NotifyToggle.applyEnabled(c, false, "通知欄按下關閉")
        }
    }
}
