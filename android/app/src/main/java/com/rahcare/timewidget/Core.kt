package com.rahcare.timewidget

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoUnit
import kotlin.math.*

// ───────── অঙ্ক ও নাম ─────────
object Bn {
    private val D = "০১২৩৪৫৬৭৮৯"
    fun digits(s: String): String = s.map { if (it in '0'..'9') D[it - '0'] else it }.joinToString("")
    fun num(n: Int): String = digits(n.toString())

    val BANGLA_MONTHS = listOf("বৈশাখ", "জ্যৈষ্ঠ", "আষাঢ়", "শ্রাবণ", "ভাদ্র", "আশ্বিন", "কার্তিক", "অগ্রহায়ণ", "পৌষ", "মাঘ", "ফাল্গুন", "চৈত্র")
    val HIJRI_MONTHS = listOf(
        "মুহাররম", "সফর", "রবিউল আউয়াল", "রবিউস সানি", "জুমাদাল উলা", "জুমাদাস সানিয়া",
        "রজব", "শা’বান", "রমজান", "শাওয়াল", "জিলকদ", "জিলহজ"
    )
}

// ───────── বাংলা তারিখ (বাংলাদেশের সংশোধিত পঞ্জিকা) ─────────
// নববর্ষ ১৪ এপ্রিল। বৈশাখ–আশ্বিন ৩১ দিন, কার্তিক–মাঘ ও চৈত্র ৩০ দিন, ফাল্গুন ২৯ দিন (লিপ ইয়ারে ৩০)
object BanglaDate {
    class D(val day: Int, val month: Int, val year: Int)

    private fun isLeap(y: Int) = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0

    fun of(date: LocalDate): D {
        val gy = if (date >= LocalDate.of(date.year, 4, 14)) date.year else date.year - 1
        var idx = ChronoUnit.DAYS.between(LocalDate.of(gy, 4, 14), date).toInt()
        val falgun = if (isLeap(gy + 1)) 30 else 29
        val len = intArrayOf(31, 31, 31, 31, 31, 31, 30, 30, 30, 30, falgun, 30)
        var m = 0
        while (m < 11 && idx >= len[m]) { idx -= len[m]; m++ }
        return D(idx + 1, m, gy - 593)
    }

    fun text(date: LocalDate) = of(date).let { Bn.num(it.day) + " " + Bn.BANGLA_MONTHS[it.month] + " " + Bn.num(it.year) }
}

// ───────── হিজরি তারিখ (উম্মুল কুরা) ─────────
object HijriDate {
    fun text(date: LocalDate): String {
        val h = HijrahDate.from(date)
        val m = h.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) - 1
        return Bn.num(h.get(java.time.temporal.ChronoField.DAY_OF_MONTH)) + " " + Bn.HIJRI_MONTHS[m] + " " +
            Bn.num(h.get(java.time.temporal.ChronoField.YEAR)) + " হি."
    }
}

// ───────── নামাজের সময় (সূর্যের অবস্থান থেকে হিসাব; PrayTimes অ্যালগরিদম) ─────────
object Prayer {
    // ফলাফল: ঘণ্টা (০–২৪) — fajr, sunrise, dhuhr, asr, sunset, isha
    class T(val fajr: Double, val sunrise: Double, val dhuhr: Double, val asr: Double, val maghrib: Double, val isha: Double)

    private fun rad(d: Double) = Math.toRadians(d)
    private fun deg(r: Double) = Math.toDegrees(r)
    private fun fixAngle(a: Double) = (a - 360.0 * floor(a / 360.0))
    private fun fixHour(a: Double) = (a - 24.0 * floor(a / 24.0))

    private fun julian(y: Int, m: Int, d: Int): Double {
        var yy = y; var mm = m
        if (mm <= 2) { yy -= 1; mm += 12 }
        val a = floor(yy / 100.0)
        val b = 2 - a + floor(a / 4.0)
        return floor(365.25 * (yy + 4716)) + floor(30.6001 * (mm + 1)) + d + b - 1524.5
    }

    private fun sunPos(jd: Double): Pair<Double, Double> { // (declination°, equation of time h)
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * sin(rad(g)) + 0.020 * sin(rad(2 * g)))
        val e = 23.439 - 0.00000036 * d
        val ra = deg(atan2(cos(rad(e)) * sin(rad(l)), cos(rad(l)))) / 15.0
        val eqt = q / 15.0 - fixHour(ra)
        val decl = deg(asin(sin(rad(e)) * sin(rad(l))))
        return Pair(decl, eqt)
    }

    /**
     * @param fajrAngle/ishaAngle ফজর ও ইশার সূর্যের কোণ (করাচি/বিশ্ব মুসলিম লীগ ধরন: ১৮°)
     * @param hanafi আসরে ছায়া দ্বিগুণ (হানাফি) হলে true
     */
    fun times(date: LocalDate, lat: Double, lng: Double, tz: Double, fajrAngle: Double = 18.0, ishaAngle: Double = 18.0, hanafi: Boolean = true): T {
        val jd = julian(date.year, date.monthValue, date.dayOfMonth) - lng / (15.0 * 24.0)
        fun midDay(t: Double) = fixHour(12.0 - sunPos(jd + t).second)
        fun angleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPos(jd + t).first
            val noon = midDay(t)
            val x = (-sin(rad(angle)) - sin(rad(decl)) * sin(rad(lat))) / (cos(rad(decl)) * cos(rad(lat)))
            val h = deg(acos(x.coerceIn(-1.0, 1.0))) / 15.0
            return noon + if (ccw) -h else h
        }
        fun asrTime(factor: Double, t: Double): Double {
            val decl = sunPos(jd + t).first
            val ang = -deg(atan(1.0 / (factor + tan(rad(abs(lat - decl))))))
            return angleTime(ang, t, false)
        }
        val adj = tz - lng / 15.0
        val fajr = angleTime(fajrAngle, 5.0 / 24, true) + adj
        val sunrise = angleTime(0.833, 6.0 / 24, true) + adj
        val dhuhr = midDay(12.0 / 24) + adj
        val asr = asrTime(if (hanafi) 2.0 else 1.0, 13.0 / 24) + adj
        val sunset = angleTime(0.833, 18.0 / 24, false) + adj
        val isha = angleTime(ishaAngle, 18.0 / 24, false) + adj
        return T(fajr, sunrise, dhuhr, asr, sunset, isha)
    }

    // ঘণ্টা (দশমিক) → মিনিট, নিকটতম মিনিটে
    fun minutes(h: Double): Int = (h * 60.0 + 0.5).toInt()

    // ১২-ঘণ্টা বাংলা ঘড়ি, AM/PM ছাড়া — যেমন ৪:৪৬
    fun fmt(min: Int, bn: Boolean = true): String {
        val m = ((min % 1440) + 1440) % 1440
        var h = m / 60 % 12; if (h == 0) h = 12
        val s = "$h:" + (m % 60).toString().padStart(2, '0')
        return if (bn) Bn.digits(s) else s
    }
}
