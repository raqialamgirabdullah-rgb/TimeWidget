package com.rahcare.timewidget

import android.content.Context
import java.net.HttpURLConnection
import java.net.URL

// GitHub-এ নতুন রিলিজ এলো কি না দেখে। "latest release" লিংক কোন ট্যাগে যায় সেটা পড়া হয় (API নয়, তাই রেট লিমিট নেই)।
// ট্যাগ v1.0.N হলে সংস্করণ নম্বর = 100 + N (build.gradle-এর versionCode-এর সাথে মেলে)।
object Updater {
    const val REPO = "raqialamgirabdullah-rgb/TimeWidget"
    private const val BASE = 100

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

    // নেটওয়ার্কে চেক করে — ব্যাকগ্রাউন্ড থ্রেড থেকে ডাকতে হবে। ব্যর্থ হলে আগের জানা অবস্থাই ফেরত দেয়।
    // ধীর নেটওয়ার্কে একবারের ব্যর্থতায় বসে না থেকে সর্বোচ্চ ৩ বার চেষ্টা করে
    fun checkNow(c: Context): Info? {
        for (attempt in 1..3) {
            try {
                val i = fetch()
                prefs(c).edit().putInt("v", i.v).putString("u", i.url).putString("t", i.tag).putLong("at", System.currentTimeMillis()).apply()
                break
            } catch (e: Exception) {
                if (attempt < 3) try { Thread.sleep(1500L) } catch (e: InterruptedException) { }
            }
        }
        return saved(c)
    }

    // উইজেটের পর্দা-হালনাগাদের সময় ব্যাকগ্রাউন্ডে ডাকা হয়: শেষ সফল চেকের ১৫ মিনিটের মধ্যে হলে কিছু করে না।
    // এতে অ্যাপ খোলার আগেই নতুন সংস্করণের খবর ফোনে জমা থাকে, খুললেই বোতাম সাথে সাথে দেখা যায়।
    fun checkIfStale(c: Context) {
        if (System.currentTimeMillis() - prefs(c).getLong("at", 0L) < 15 * 60 * 1000L) return
        checkNow(c)
    }
}
