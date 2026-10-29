package com.rahcare.timewidget

import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলো কি না দেখে। "latest release" লিংক কোন ট্যাগে যায় সেটা পড়া হয় (API নয়, তাই রেট লিমিট নেই)।
// ট্যাগ v1.0.N হলে সংস্করণ নম্বর = 100 + N (build.gradle-এর versionCode-এর সাথে মেলে)।
object Updater {
    const val REPO = "raqialamgirabdullah-rgb/TimeWidget"
    private const val BASE = 100
    private const val JOB_ID = 4701

    class Info(val v: Int, val url: String, val tag: String)

    fun curVer(c: Context): Int {
        @Suppress("DEPRECATION")
        return c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun fetch(): Info {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            cn.instanceFollowRedirects = false
            cn.connectTimeout = 10000
            cn.readTimeout = 10000
            val tag = (cn.getHeaderField("Location") ?: "").substringAfterLast('/')
            val n = tag.substringAfterLast('.').toInt()
            return Info(BASE + n, "https://github.com/$REPO/releases/download/$tag/TimeWidget.apk", tag)
        } finally {
            cn.disconnect()
        }
    }

    // শেষবার জানা নতুন সংস্করণ (বর্তমানের চেয়ে বড় হলে)
    fun saved(c: Context): Info? {
        val sp = prefs(c)
        val v = sp.getInt("v", 0)
        val u = sp.getString("u", null)
        val t = sp.getString("t", "") ?: ""
        return if (u != null && v > curVer(c)) Info(v, u, t) else null
    }

    // নেটওয়ার্কে চেক করে — ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। ব্যর্থ হলে আগের জানা অবস্থাই ফেরত দেয়।
    fun checkNow(c: Context): Info? {
        try {
            val i = fetch()
            prefs(c).edit().putInt("v", i.v).putString("u", i.url).putString("t", i.tag).apply()
        } catch (e: Exception) {
        }
        return saved(c)
    }

    // ───────── স্বয়ংক্রিয় আপডেট ─────────
    // প্রতি ~৬ ঘণ্টায় (নেট থাকলে) ফোন নিজেই UpdateJob চালায়; রিবুটের পরও থাকে। অ্যাপ খুলতে হয় না।
    fun schedule(c: Context) {
        try {
            val js = c.getSystemService(JobScheduler::class.java)
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(c, UpdateJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(6 * 60 * 60 * 1000L)
                .setPersisted(true)
                .build()
            js.schedule(job)
        } catch (e: Exception) {
        }
    }

    // নতুন সংস্করণ থাকলে নিজে নামিয়ে ইনস্টল শুরু করে (অনুমতি থাকলে)। ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে।
    fun autoUpdate(c: Context) {
        val ctx = c.applicationContext
        val info = checkNow(ctx) ?: return
        if (!ctx.packageManager.canRequestPackageInstalls()) return
        val sp = prefs(ctx)
        val now = System.currentTimeMillis()
        if (now - sp.getLong("ta", 0L) < 10 * 60 * 1000L) return // বারবার ব্যর্থ হলে ১০ মিনিটে একবারের বেশি নয়
        sp.edit().putLong("ta", now).apply()
        install(ctx, info)
    }

    // ডাউনলোড + PackageInstaller-এ ইনস্টল। ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে।
    // সফলভাবে ইনস্টলে পাঠানো গেলে null, না হলে ত্রুটির বার্তা ফেরত দেয়।
    fun install(c: Context, u: Info, onProgress: ((Int) -> Unit)? = null): String? {
        val ctx = c.applicationContext
        val inst = ctx.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) p.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = inst.createSession(p)
        try {
            val s = inst.openSession(id)
            val cn = URL(u.url).openConnection() as HttpURLConnection
            cn.connectTimeout = 15000
            cn.readTimeout = 30000
            if (cn.responseCode != 200) throw Exception("HTTP ${cn.responseCode}")
            val total = cn.contentLengthLong
            cn.inputStream.use { ins ->
                s.openWrite("a", 0, -1).use { out ->
                    val buf = ByteArray(32 * 1024)
                    var done = 0L
                    var last = -1
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0 && onProgress != null) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                    s.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, UpdateReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            s.commit(pi.intentSender)
            return null
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (x: Exception) { }
            return e.javaClass.simpleName
        }
    }
}
