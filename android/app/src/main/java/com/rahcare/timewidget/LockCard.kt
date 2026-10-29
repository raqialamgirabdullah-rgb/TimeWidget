package com.rahcare.timewidget

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

// লক স্ক্রিনে উইজেট: হোম স্ক্রিনের হুবহু একই নকশা নোটিফিকেশনের ভেতরে (বড় করলে পুরো উইজেট)
object LockCard {
    private const val CH = "lockcard"
    private const val ID = 4242

    fun update(ctx: Context, s: ClockWidget.Snap) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val c = Cfg(ctx)
            if (!c.lockCard) { nm.cancel(ID); return }
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val ch = NotificationChannel(CH, "লক স্ক্রিনে উইজেট", NotificationManager.IMPORTANCE_LOW)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CH)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle("সূর্যোদয় " + Prayer.fmt(s.sunrise, c.bnDigits) + " · সূর্যাস্ত " + Prayer.fmt(s.sunset, c.bnDigits))
                .setContentText(s.hijri + " · " + s.bangla)
                .setStyle(Notification.DecoratedCustomViewStyle())
                .setCustomBigContentView(ClockWidget.buildNotif(ctx, s))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_STATUS)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(open)
                .build()
            nm.notify(ID, n)
        } catch (e: Exception) {
        }
    }
}
