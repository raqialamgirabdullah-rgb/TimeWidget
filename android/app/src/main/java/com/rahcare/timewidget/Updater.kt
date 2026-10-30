package com.rahcare.timewidget

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলে নিজে থেকে ধরে — অ্যাপ খুলতে হয় না:
//  • উইজেটের ঘড়ির অ্যালার্মে ব্যাকগ্রাউন্ডে প্রায় ১০ মিনিট পরপর খোঁজে (ClockWidget)
//  • পেলে নিজে নামিয়ে ইনস্টল করে (Android ১২+ এবং "অজানা সোর্স" অনুমতি থাকলে নিশ্চিত-পর্দা ছাড়াই)
//  • না পারলে একটি নোটিফিকেশন দেয়; চাপলেই আপডেট শুরু হয়
//  • অ্যাপ খোলা থাকলে বোতাম দেখায়
// "latest release" লিংক কোন ট্যাগে যায় সেটা পড়া হয় (API নয়, তাই রেট লিমিট নেই)।
// ট্যাগ v1.0.N হলে সংস্করণ নম্বর = 100 + N (build.gradle-এর versionCode-এর সাথে মেলে)।
object Updater {
    const val REPO = "raqialamgirabdullah-rgb/TimeWidget"
    private const val BASE = 100
    private const val MAX_AUTO_TRIES = 3   // একটি সংস্করণের জন্য ব্যাকগ্রাউন্ডে সর্বোচ্চ এতবার নিজে ইনস্টলের চেষ্টা
    private const val MAX_SILENT_FAILS = 3 // নীরব ইনস্টল পরপর এতবার আটকালে আর নীরব চেষ্টা নয়, শুধু নোটিফিকেশন

    class Info(val v: Int, val url: String, val tag: String)

    // অ্যাপের পর্দা এখন সামনে খোলা আছে কি না (MainActivity ঠিক করে)
    @Volatile var fg = false
    // ইনস্টল বাতিল/ব্যর্থ হলে সামনের পর্দা বোতাম আগের অবস্থায় ফেরায়
    @Volatile var onInstallFail: (() -> Unit)? = null

    fun curVer(c: Context): Int {
        @Suppress("DEPRECATION")
        return c.packageManager.getPackageInfo(c.packageName, 0).versionCode
    }

    private fun prefs(c: Context) = c.getSharedPreferences("update", Context.MODE_PRIVATE)

    private fun fetch(): Info {
        val cn = URL("https://github.com/$REPO/releases/latest").openConnection() as HttpURLConnection
        try {
            cn.instanceFollowRedirects = false
            cn.useCaches = false
            cn.setRequestProperty("Cache-Control", "no-cache")
            cn.connectTimeout = 6000
            cn.readTimeout = 6000
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

    // নেটওয়ার্কে চেক করে (ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে); ধীর নেটে সর্বোচ্চ ৩ বার চেষ্টা। ব্যর্থ হলে আগের জানা অবস্থাই ফেরত দেয়।
    fun checkNow(c: Context, attempts: Int = 3): Info? {
        for (a in 1..attempts) {
            try {
                val i = fetch()
                prefs(c).edit().putInt("v", i.v).putString("u", i.url).putString("t", i.tag).putLong("at", System.currentTimeMillis()).apply()
                break
            } catch (e: Exception) {
                if (a < attempts) try { Thread.sleep(1500L) } catch (x: InterruptedException) { }
            }
        }
        return saved(c)
    }

    // নীরব (নিশ্চিত-পর্দা ছাড়া) ইনস্টল চেষ্টা করা যাবে কি না: শুধু Android 12+, এবং ফোন পরপর আটকে না থাকলে
    fun silentAllowed(c: Context): Boolean =
        Build.VERSION.SDK_INT >= 31 && prefs(c).getInt("silent_fail", 0) < MAX_SILENT_FAILS

    fun noteSilentFail(c: Context) {
        val sp = prefs(c)
        sp.edit().putInt("silent_fail", sp.getInt("silent_fail", 0) + 1).apply()
    }

    // ব্যাকগ্রাউন্ড চেক (ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে): শেষ চেকের minGapMs-এর মধ্যে হলে কিছু করে না।
    // নতুন সংস্করণ পেলে নিজে নামিয়ে ইনস্টল করে; না পারলে (অনুমতি নেই / ফোন আটকালে / অ্যাপ সামনে খোলা) একবার নোটিফিকেশন দেয়।
    fun backgroundCheck(c: Context, minGapMs: Long) {
        val sp = prefs(c)
        if (System.currentTimeMillis() - sp.getLong("at", 0L) < minGapMs) return
        val i = checkNow(c, 2) ?: return

        val tries = if (sp.getInt("auto_v", 0) == i.v) sp.getInt("auto_n", 0) else 0
        val can = silentAllowed(c) && !fg && c.packageManager.canRequestPackageInstalls() && tries < MAX_AUTO_TRIES
        if (can) {
            sp.edit().putInt("auto_v", i.v).putInt("auto_n", tries + 1).apply()
            try {
                installNow(c, i.url, true, {}, {})
                return
            } catch (e: Exception) {
            }
        }
        if (sp.getInt("upd_n", 0) == i.v) return
        sp.edit().putInt("upd_n", i.v).apply()
        notice(c)
    }

    // APK নামিয়ে PackageInstaller-এ জমা দেয়। silent = true হলে (Android 12+) ফোন অনুমতি দিলে নিশ্চিত-পর্দা ছাড়াই বসে;
    // না দিলে UpdateReceiver নিশ্চিত-পর্দা চায়। ব্যর্থ হলে ব্যতিক্রম ছোঁড়ে।
    fun installNow(c: Context, url: String, silent: Boolean, onPct: (Int) -> Unit, onCommit: () -> Unit) {
        val inst = c.packageManager.packageInstaller
        val p = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) {
            p.setRequireUserAction(
                if (silent) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                else PackageInstaller.SessionParams.USER_ACTION_REQUIRED
            )
        }
        val id = inst.createSession(p)
        try {
            val s = inst.openSession(id)
            val cn = URL(url).openConnection() as HttpURLConnection
            try {
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
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    onPct(pct)
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
            } finally {
                cn.disconnect()
            }
            val pi = PendingIntent.getBroadcast(
                c, id, Intent(c, UpdateReceiver::class.java).putExtra("silent", silent),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            // ফল জানতে: নীরব চেষ্টা ছিল কি না, আর ফোন নিশ্চিত-পর্দা চেয়েছিল কি না (UpdateReceiver ঠিক করে)
            prefs(c).edit().putBoolean("inst_silent", silent).putBoolean("inst_pending", false).apply()
            onCommit()
            s.commit(pi.intentSender)
        } catch (e: Exception) {
            try { inst.abandonSession(id) } catch (x: Exception) { }
            throw e
        }
    }

    private fun channel(c: Context, id: String, name: String, imp: Int): NotificationManager {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(id, name, imp))
        return nm
    }

    // নতুন সংস্করণ এসেছে: চাপলে অ্যাপ খুলে আপডেট নিজেই শুরু হয়
    private fun notice(c: Context) {
        try {
            val nm = channel(c, "update", "টাইম উইজেট আপডেট", NotificationManager.IMPORTANCE_DEFAULT)
            val go = Intent(c, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("upd", true)
            val pi = PendingIntent.getActivity(c, 8, go, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(8, Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("টাইম উইজেটের নতুন সংস্করণ এসেছে")
                .setContentText("চাপুন — আপডেট শুরু হবে")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
    }

    // ব্যাকগ্রাউন্ডে নিশ্চিত করার পর্দা খোলা যায় না, তাই নোটিফিকেশনে চাপলে সেটি খোলে
    fun confirmNotice(c: Context, ui: Intent) {
        try {
            val nm = channel(c, "update", "টাইম উইজেট আপডেট", NotificationManager.IMPORTANCE_DEFAULT)
            val pi = PendingIntent.getActivity(c, 12, ui, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(12, Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle("টাইম উইজেটের নতুন সংস্করণ তৈরি")
                .setContentText("চাপুন — ইনস্টল নিশ্চিত করুন")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
    }

    fun failNotice(c: Context, msg: String) {
        try {
            val nm = channel(c, "update", "টাইম উইজেট আপডেট", NotificationManager.IMPORTANCE_DEFAULT)
            nm.notify(9, Notification.Builder(c, "update")
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("আপডেট হয়নি")
                .setContentText(msg)
                .setStyle(Notification.BigTextStyle().bigText(msg))
                .setAutoCancel(true)
                .build())
        } catch (e: Exception) {
        }
    }

    fun clearNotice(c: Context) {
        try {
            val nm = c.getSystemService(NotificationManager::class.java)
            nm.cancel(8); nm.cancel(9); nm.cancel(12)
        } catch (e: Exception) {
        }
    }

    // আপডেটের পর নতুন সংস্করণ চালু হলে (MY_PACKAGE_REPLACED): নীরব ইনস্টল কাজ করেছে কি না মিলিয়ে দেখে, পুরোনো নোটিফিকেশন মোছে
    fun afterUpdate(c: Context) {
        val sp = prefs(c)
        if (sp.getBoolean("inst_silent", false) && !sp.getBoolean("inst_pending", false)) {
            sp.edit().putInt("silent_fail", 0).apply() // নীরব আপডেট কাজ করেছে
        }
        sp.edit().remove("inst_silent").remove("inst_pending").remove("upd_n").apply()
        clearNotice(c)
    }
}
