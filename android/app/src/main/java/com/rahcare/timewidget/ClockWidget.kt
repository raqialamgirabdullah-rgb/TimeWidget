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

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) { LocationUpdater.register(ctx); refresh(ctx) }
    override fun onEnabled(ctx: Context) { LocationUpdater.register(ctx); refresh(ctx) }
    override fun onDisabled(ctx: Context) = cancel(ctx)
    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, opts: Bundle) = refresh(ctx)

    override fun onReceive(ctx: Context, intent: Intent) {
        super.onReceive(ctx, intent)
        when (intent.action) {
            ACTION_PICK -> pick(ctx, intent.getIntExtra("idx", -1))
            // রিবুট বা অ্যাপ আপডেটের পর অবস্থানের অনুরোধ আবার চালু করতে হয়
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> { LocationUpdater.register(ctx); refresh(ctx) }
            ACTION_TICK, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_DATE_CHANGED -> {
                refresh(ctx)
                // ব্যাকগ্রাউন্ডে নতুন সংস্করণ খুঁজে রাখে (১৫ মিনিটে একবার), যাতে অ্যাপ খুললেই আপডেট বোতাম দেখা যায়
                if (intent.action == ACTION_TICK) {
                    val pr = goAsync()
                    val app = ctx.applicationContext
                    Thread {
                        try { Updater.checkIfStale(app) } catch (e: Exception) { } finally { pr.finish() }
                    }.start()
                }
            }
        }
    }

    // ws/we = প্রতিটি ওয়াক্তের শুরু ও শেষ (আজ মধ্যরাত থেকে মিনিট; ইশার শেষ পরদিনের ফজরে, তাই ১৪৪০ ছাড়াতে পারে / ফজরের আগে ইশার শুরু ঋণাত্মক হতে পারে); nowMin = এখন (ভগ্নাংশসহ মিনিট)
    class Snap(
        val hijri: String, val bangla: String, val min: IntArray, val cur: Int, val nextAt: Long, val sunrise: Int, val sunset: Int,
        val ws: IntArray, val we: IntArray, val nowMin: Double
    )

    companion object {
        const val ACTION_TICK = "com.rahcare.timewidget.TICK"
        const val ACTION_PICK = "com.rahcare.timewidget.PICK"
        private const val TICK_MS = 5 * 60 * 1000L // প্রগ্রেস বার নতুন করে আঁকার ব্যবধান
        private const val DUHA_AFTER_SUNRISE = 24 // সালাতুদ্দোহা শুরু: সূর্যোদয়ের ২৪ মিনিট পর (ইসলামিক ফাউন্ডেশনের স্থায়ী সময়সূচির নোট: নিষিদ্ধ সময়)
        private const val DUHA_BEFORE_DHUHR = 6 // সালাতুদ্দোহা শেষ: যোহরের লেখা সময়ের ৬ মিনিট আগে (ইসলামিক ফাউন্ডেশনের নোট)
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

            // প্রতিটি ওয়াক্তের শুরু ও শেষ: ফজর→সূর্যোদয়, যোহর→আসরের শুরু, আসর→সূর্যাস্ত, মাগরিব→ইশার শুরু, ইশা→পরদিনের ফজর
            val sunriseMin = Prayer.minutes(t.sunrise)
            val sunsetMin = Prayer.minutes(t.maghrib)
            val ws = m.copyOf()
            val we = intArrayOf(sunriseMin, m[2], sunsetMin, m[4], 0)
            if (nowMin < m[0]) {
                // ফজরের আগে: চলতি ইশা গতকালের, শেষ আজকের ফজরে
                val y = Prayer.times(date.minusDays(1), c.lat(), c.lng(), tz, 18.0, 18.0, c.hanafi)
                ws[4] = Prayer.minutes(y.isha) + c.off(4) - 1440
                we[4] = m[0]
            } else {
                val tomorrow = date.plusDays(1)
                val tz2 = zone.rules.getOffset(tomorrow.atStartOfDay(zone).toInstant()).totalSeconds / 3600.0
                val n = Prayer.times(tomorrow, c.lat(), c.lng(), tz2, 18.0, 18.0, c.hanafi)
                we[4] = Prayer.minutes(n.fajr) + c.off(0) + 1440
            }
            val nowF = nowMin + now.second / 60.0

            return Snap(
                HijriDate.text(date.plusDays(c.hijriShift().toLong())),
                BanglaDate.text(date.plusDays(c.banglaAdj.toLong())),
                m, cur, next, sunriseMin, sunsetMin, ws, we, nowF
            )
        }

        // দুই তারিখ এক লাইনে ধরাতে উইজেটের প্রস্থ মেপে লেখা প্রয়োজনে ছোট হয়
        private fun fitSp(ctx: Context, a: String, b: String, baseSp: Float, widthDp: Int): Float {
            val dm = ctx.resources.displayMetrics
            val avail = (widthDp - 28 - 8) * dm.density * 0.93f
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, baseSp, dm)
            val need = p.measureText(a) + p.measureText(b)
            return if (need <= avail || need <= 0f) baseSp else (baseSp * avail / need).coerceAtLeast(baseSp * 0.55f)
        }

        private fun build(ctx: Context, s: Snap, widthDp: Int, layout: Int = R.layout.widget_v2): RemoteViews {
            val c = Cfg(ctx)
            val rv = RemoteViews(ctx.packageName, layout)
            val k = c.scale / 100f
            fun size(id: Int, sp: Float) = rv.setTextViewTextSize(id, TypedValue.COMPLEX_UNIT_SP, sp * k)

            rv.setTextViewText(R.id.zz_loc, c.placeName())
            size(R.id.zz_loc, 12f)
            rv.setTextViewText(R.id.hijri, s.hijri)
            rv.setTextViewText(R.id.bangla, s.bangla)
            size(R.id.time, 40f); size(R.id.ampm, 12f); size(R.id.endate, 15f)
            val dsp = fitSp(ctx, s.hijri, s.bangla, 12f * k, widthDp)
            rv.setTextViewTextSize(R.id.hijri, TypedValue.COMPLEX_UNIT_SP, dsp)
            rv.setTextViewTextSize(R.id.bangla, TypedValue.COMPLEX_UNIT_SP, dsp)
            rv.setTextViewText(R.id.zz_sunrise, "সূর্যোদয় " + Prayer.fmt(s.sunrise, c.bnDigits))
            rv.setTextViewText(R.id.zz_sunset, "সূর্যাস্ত " + Prayer.fmt(s.sunset, c.bnDigits))
            size(R.id.zz_sunrise, 11f); size(R.id.zz_sunset, 11f)
            for (i in 0..4) {
                rv.setTextViewText(NAME[i], NAMES[i])
                rv.setTextViewText(TIME[i], Prayer.fmt(s.min[i], c.bnDigits))
                size(NAME[i], 11f); size(TIME[i], 14f)
                // চলতি ওয়াক্তের ঘরটি হালকা সবুজে চিহ্নিত
                rv.setInt(CELL[i], "setBackgroundResource", if (i == s.cur) R.drawable.cell_on else 0)
                // ঘরে চাপলে নিচের সারিতে সেই ওয়াক্তের শুরু-শেষ দেখায়; একই ঘরে আবার চাপলে চলতি ওয়াক্তে ফেরে
                rv.setOnClickPendingIntent(CELL[i], pickIntent(ctx, i))
            }
            // নিচের সারি: ওয়াক্তের শুরু (বামে) · প্রগ্রেস বার · শেষ (ডানে); ডিফল্টে চলতি ওয়াক্ত।
            // ফজর শেষ (সূর্যোদয়) থেকে যোহর শুরুর মাঝের ফাঁকে ডিফল্টে সালাতুদ্দোহা দেখায় (কোনো ওয়াক্তের ঘরে চাপলে সেটিই দেখায়)
            val picked = c.pick in 0..4 && c.pickCur == s.cur
            val sel = if (picked) c.pick else s.cur
            val duha = !picked && s.cur == 0 && s.nowMin >= s.sunrise && s.nowMin < s.min[1]
            val bs = if (duha) s.sunrise + DUHA_AFTER_SUNRISE else s.ws[sel]
            val be = if (duha) (s.min[1] - DUHA_BEFORE_DHUHR).coerceAtLeast(bs + 1) else s.we[sel]
            val span = (be - bs).coerceAtLeast(1)
            val frac = ((s.nowMin - bs) / span).coerceIn(0.0, 1.0)
            rv.setTextViewText(R.id.zz_ws, (if (duha) "সালাতুদ্দোহা শুরু " else "ওয়াক্ত শুরু ") + Prayer.fmt(bs, c.bnDigits))
            rv.setTextViewText(R.id.zz_we, (if (duha) "শেষ " else "ওয়াক্ত শেষ ") + Prayer.fmt(be, c.bnDigits))
            size(R.id.zz_ws, 11f); size(R.id.zz_we, 11f)
            rv.setProgressBar(R.id.zz_wbar, 1000, (frac * 1000).toInt(), false)
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.root, open)
            return rv
        }

        private fun pickIntent(ctx: Context, i: Int): PendingIntent = PendingIntent.getBroadcast(
            ctx, 10 + i, Intent(ctx, ClockWidget::class.java).setAction(ACTION_PICK).putExtra("idx", i),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // কোনো ওয়াক্তের ঘরে চাপ: সেটি বেছে নেয়; আবার চাপলে চলতি ওয়াক্তে ফেরে
        private fun pick(ctx: Context, i: Int) {
            if (i !in 0..4) return
            val c = Cfg(ctx)
            val s = compute(ctx)
            val already = c.pick == i && c.pickCur == s.cur
            c.pick = if (already) -1 else i
            c.pickCur = s.cur
            refresh(ctx)
        }

        private fun tickIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
            ctx, 0, Intent(ctx, ClockWidget::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        fun cancel(ctx: Context) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickIntent(ctx))
        }

        // সব উইজেট নতুন করে আঁকে এবং পরের বদলের (পরের ওয়াক্ত বা মধ্যরাত) জন্য অ্যালার্ম বসায়
        // আগের সংস্করণে চালু হওয়া লক স্ক্রিন নোটিফিকেশন সরিয়ে দেয় (এই সুবিধা আর নেই)
        private fun removeOldLockCard(ctx: Context) {
            try {
                val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                nm.cancel(4242)
                nm.deleteNotificationChannel("lockcard")
            } catch (e: Exception) {
            }
        }

        fun refresh(ctx: Context) {
            removeOldLockCard(ctx)
            try {
                val mgr = AppWidgetManager.getInstance(ctx)
                val ids = mgr.getAppWidgetIds(ComponentName(ctx, ClockWidget::class.java))
                if (ids.isEmpty()) { cancel(ctx); return }
                LocationUpdater.sync(ctx) // ফোনের সর্বশেষ জানা অবস্থান বদলে থাকলে এখানেই ধরে নেয়
                val s = compute(ctx)
                for (id in ids) {
                    val w = mgr.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).let { if (it > 0) it else 250 }
                    mgr.updateAppWidget(id, build(ctx, s, w))
                }
                (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
                    .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, minOf(s.nextAt, System.currentTimeMillis() + TICK_MS), tickIntent(ctx))
            } catch (e: Exception) {
            }
        }
    }
}
