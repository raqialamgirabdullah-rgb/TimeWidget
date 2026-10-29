package com.rahcare.timewidget

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

// PackageInstaller-এর ফল এখানে আসে; ফোন নিশ্চিত করতে চাইলে সেই পর্দা খোলে (ব্যাকগ্রাউন্ডে থাকলে নোটিফিকেশন দেয়)
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val ui = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val st = ActivityManager.RunningAppProcessInfo()
                ActivityManager.getMyMemoryState(st)
                if (st.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    try {
                        c.startActivity(ui)
                    } catch (e: Exception) {
                        Toast.makeText(c, "নিশ্চিত করার পর্দা খুলল না — আবার চেষ্টা করুন", Toast.LENGTH_LONG).show()
                    }
                } else {
                    notifyConfirm(c, ui)
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            else -> Toast.makeText(
                c, "আপডেট হয়নি: " + (i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun notifyConfirm(c: Context, ui: Intent) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("update", "অ্যাপ আপডেট", NotificationManager.IMPORTANCE_DEFAULT))
            val pi = PendingIntent.getActivity(c, 1, ui, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("টাইম উইজেটের নতুন সংস্করণ তৈরি")
                .setContentText("আপডেট শেষ করতে এখানে ট্যাপ করুন")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(77, n)
        } catch (e: Exception) {
        }
    }
}
