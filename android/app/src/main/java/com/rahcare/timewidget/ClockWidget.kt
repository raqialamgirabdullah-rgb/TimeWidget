package com.rahcare.timewidget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import java.time.ZonedDateTime

// হোম স্ক্রিনের একটি উইজেট: সময় + ইংরেজি তারিখ, আরবি + বাংলা তারিখ, পাঁচ ওয়াক্ত নামাজ
class ClockWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) = refresh(ctx)
    override fun onEnabled(ctx: Context) = refresh(ctx)
    override fun onDisabled(ctx: Context) = cancel(ctx)

    // উইজেট ছোট-বড় করলে লেখার মাপ নতুন করে মিলিয়ে নেয়
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_TICK, Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> refresh(ctx)
        }
    }

    class Snap(val hijri: String, val bangla: String, val min: IntArray, val cur: Int, val nextAt: Long, val sunrise: Int, val sunset: Int)

    companion object {
        const val ACTION_TICK = "com.rahcare.timewidget.TICK"
        val NAMES = arrayOf("ফজর", "যোহর", "আসর", "মাগরিব", "ইশা")
        private val CELL = intArrayOf(R.id.c0, R.id.c1, R.id.c2, R.id.c3, R.id.c4)
        private val NAME = intArrayOf(R.id.n0, R.id.n1, R.id.n2, R.id.n3, R.id.n4)
        private val TIME = intArrayOf(R.id.t0, R.id.t1, R.id.t2, R.id.t3, R.id.t4)

        // আজকের সব হিসাব
        fun compute(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): Snap {
            val c = Cfg(ctx)
            val zone = now.zone
            val date = now.toLocalDate()
            val tz = zone.rules.getOffset(now.toInstant()).totalSeconds / 3600.0
            val t = Prayer.times(date, c.lat(), c.lng(), tz, 18.0, 18.0, c.hanafi)
            val m = intArrayOf(
                Prayer.minutes(t.fajr) + c.off(0), Prayer.minutes(t.dhuhr) + c.off(1), Prayer.minutes(t.asr) + c.off(2),
                Prayer.minutes(t.maghrib) + c.off(3), Prayer.minutes(t.isha) + c.off(4)
            )
            val nowMin = now.hour * 60 + now.minute
            var cur = 4 // ফজরের আগে হলে আগের রাতের ইশার ওয়াক্ত চলছে
            for (i in 0..4) if (m[i] <= nowMin) cur = i

            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            var next = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() + 5000L
            for (i in 0..4) if (m[i] > nowMin) next = minOf(next, start + m[i] * 60000L)

            return Snap(
                HijriDate.text(date.plusDays(c.hijriAdj.toLong())),
                BanglaDate.text(date.plusDays(c.banglaAdj.toLong())),
                m, cur, next,
                Prayer.minutes(t.sunrise), Prayer.minutes(t.maghrib) // সূর্যাস্ত = মাগরিবের আগের কাঁচা সময় (সতর্কতার মিনিট ছাড়া)
            )
        }

        // দুই তারিখ এক লাইনে ধরাতে লেখার মাপ: উইজেটের প্রস্থ মেপে প্রয়োজনে একসাথে ছোট করে (সর্বোচ্চ ৪৫% পর্যন্ত)
        private fun fitSp(ctx: Context, a: String, b: String, baseSp: Float, widthDp: Int): Float {
            val dm = ctx.resources.displayMetrics
            val avail = (widthDp - 28 - 8) * dm.density * 0.93f // দুই পাশে ১৪dp করে ফাঁক, মাঝে ৮dp, সামান্য নিরাপত্তা
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, baseSp, dm)
            val need = p.measureText(a) + p.measureText(b)
            return if (need <= avail || need <= 0f) baseSp else (baseSp * avail / need).coerceAtLeast(baseSp * 0.55f)
        }

        private fun build(ctx: Context, s: Snap, widthDp: Int): RemoteViews {
            val c = Cfg(ctx)
            val rv = RemoteViews(ctx.packageName, R.layout.widget)
            val k = c.scale / 100f
            fun size(id: Int, sp: Float) = rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * k)

            rv.setTextViewText(R.id.hijri, s.hijri)
            rv.setTextViewText(R.id.bangla, s.bangla)
            rv.setTextViewText(R.id.sunrise, "সূর্যোদয় " + Prayer.fmt(s.sunrise, c.bnDigits))
            rv.setTextViewText(R.id.sunset, "সূর্যাস্ত " + Prayer.fmt(s.sunset, c.bnDigits))
            size(R.id.sunrise, 11f); size(R.id.sunset, 11f)
            size(R.id.time, 40f); size(R.id.ampm, 12f); size(R.id.endate, 15f)
            val dsp = fitSp(ctx, s.hijri, s.bangla, 12f * k, widthDp)
            rv.setTextViewTextSize(R.id.hijri, TypedValue.COMPLEX_UNIT_SP, dsp)
            rv.setTextViewTextSize(R.id.bangla, TypedValue.COMPLEX_UNIT_SP, dsp)
            for (i in 0..4) {
                rv.setTextViewText(NAME[i], NAMES[i])
                rv.setTextViewText(TIME[i], Prayer.fmt(s.min[i], c.bnDigits))
                size(NAME[i], 11f); size(TIME[i], 14f)
                // চলতি ওয়াক্তের ঘরটি হালকা সবুজে চিহ্নিত
                rv.setInt(CELL[i], "setBackgroundResource", if (i == s.cur) R.drawable.cell_on else 0)
            }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.root, open)
            return rv
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ClockWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun cancel(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(ctx))
        }

        // সব উইজেট নতুন করে আঁকে এবং পরের বদলের (পরের ওয়াক্ত বা মধ্যরাত) জন্য অ্যালার্ম বসায়
        fun refresh(ctx: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, ClockWidget::class.java))
                if (ids.isEmpty()) { cancel(ctx); return }
                val s = compute(ctx)
                for (id in ids) {
                    val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).let { if (it > 0) it else 250 }
                    mgr.updateAppWidget(id, build(ctx, s, w))
                }
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, s.nextAt, tickIntent(ctx))
                Updater.schedule(ctx) // নতুন সংস্করণ নিজে খোঁজার কাজ (নেট থাকলে) চালু আছে কি না নিশ্চিত করে
            } catch (e: Exception) {
            }
        }
    }
}
