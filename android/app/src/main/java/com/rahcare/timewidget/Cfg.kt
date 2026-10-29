package com.rahcare.timewidget

import android.content.Context

// স্থানের তালিকা: নাম, অক্ষাংশ, দ্রাঘিমাংশ
object Places {
    val list = listOf(
        Triple("ঢাকা", 23.8103, 90.4125), Triple("চট্টগ্রাম", 22.3569, 91.7832), Triple("রাজশাহী", 24.3745, 88.6042),
        Triple("খুলনা", 22.8456, 89.5403), Triple("বরিশাল", 22.7010, 90.3535), Triple("সিলেট", 24.8949, 91.8687),
        Triple("রংপুর", 25.7439, 89.2752), Triple("ময়মনসিংহ", 24.7471, 90.4203), Triple("কুমিল্লা", 23.4607, 91.1809),
        Triple("নারায়ণগঞ্জ", 23.6238, 90.5000), Triple("গাজীপুর", 23.9999, 90.4203), Triple("ফরিদপুর", 23.6070, 89.8429),
        Triple("বগুড়া", 24.8465, 89.3773), Triple("যশোর", 23.1664, 89.2081), Triple("কক্সবাজার", 21.4272, 92.0058),
        Triple("নোয়াখালী", 22.8696, 91.0995), Triple("দিনাজপুর", 25.6279, 88.6332), Triple("টাঙ্গাইল", 24.2513, 89.9167),
        Triple("পাবনা", 24.0064, 89.2372)
    )
    val CUSTOM get() = list.size
    fun names(): List<String> = list.map { it.first } + "অন্য স্থান (অক্ষাংশ/দ্রাঘিমাংশ লিখুন)"
}

// সব সেটিংস এক জায়গায়
class Cfg(ctx: Context) {
    private val sp = ctx.getSharedPreferences("cfg", Context.MODE_PRIVATE)

    var place: Int
        get() = sp.getInt("place", 0)
        set(v) = sp.edit().putInt("place", v).apply()
    var customLat: String
        get() = sp.getString("clat", "23.8103") ?: "23.8103"
        set(v) = sp.edit().putString("clat", v).apply()
    var customLng: String
        get() = sp.getString("clng", "90.4125") ?: "90.4125"
        set(v) = sp.edit().putString("clng", v).apply()
    var hanafi: Boolean
        get() = sp.getBoolean("hanafi", true)
        set(v) = sp.edit().putBoolean("hanafi", v).apply()
    // বাংলাদেশে চাঁদ দেখা সাধারণত সৌদির একদিন পরে, তাই হিজরি তারিখ ডিফল্টে −১
    var hijriAdj: Int
        get() = sp.getInt("hijri", -1)
        set(v) = sp.edit().putInt("hijri", v).apply()
    var banglaAdj: Int
        get() = sp.getInt("bangla", 0)
        set(v) = sp.edit().putInt("bangla", v).apply()
    var bnDigits: Boolean
        get() = sp.getBoolean("bnd", true)
        set(v) = sp.edit().putBoolean("bnd", v).apply()
    // লেখার আকার (শতাংশ)
    var scale: Int
        get() = sp.getInt("scale", 100)
        set(v) = sp.edit().putInt("scale", v).apply()

    // উইজেটের নিচের সারিতে যে ওয়াক্তের সময় দেখানো হবে বলে চাপা হয়েছে (-১ = চলতি ওয়াক্ত); pickCur = চাপার সময় চলতি ওয়াক্ত কোনটা ছিল
    // (ওয়াক্ত বদলালে নিজে থেকে চলতি ওয়াক্তে ফেরে)
    var pick: Int
        get() = sp.getInt("pick", -1)
        set(v) = sp.edit().putInt("pick", v).apply()
    var pickCur: Int
        get() = sp.getInt("pickCur", -1)
        set(v) = sp.edit().putInt("pickCur", v).apply()

    // ফজর, যোহর, আসর, মাগরিব, ইশা — মিনিটের সমন্বয়। ডিফল্ট: যোহর +১, মাগরিব +৩ (সূর্যাস্তের পর সতর্কতার মিনিট)
    private val defOff = intArrayOf(0, 1, 0, 3, 0)
    fun off(i: Int): Int = sp.getInt("off$i", defOff[i])
    fun setOff(i: Int, v: Int) = sp.edit().putInt("off$i", v).apply()

    // স্বয়ংক্রিয় অবস্থান: চালু থাকলে ও ফোন থেকে অবস্থান পাওয়া গেলে হাতে বাছা স্থানের বদলে সেটাই ব্যবহার হয়
    var auto: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()
    var autoLat: String
        get() = sp.getString("alat", "") ?: ""
        set(v) = sp.edit().putString("alat", v).apply()
    var autoLng: String
        get() = sp.getString("alng", "") ?: ""
        set(v) = sp.edit().putString("alng", v).apply()
    var autoAt: Long // সর্বশেষ কখন অবস্থান বদলেছে/ধরা পড়েছে
        get() = sp.getLong("aat", 0L)
        set(v) = sp.edit().putLong("aat", v).apply()

    // স্বয়ংক্রিয় অবস্থান (অক্ষাংশ, দ্রাঘিমাংশ); বন্ধ থাকলে বা এখনো না পেলে null
    fun autoFix(): Pair<Double, Double>? {
        if (!auto) return null
        val a = autoLat.toDoubleOrNull() ?: return null
        val b = autoLng.toDoubleOrNull() ?: return null
        return Pair(a, b)
    }

    fun lat(): Double = autoFix()?.first ?: if (place in Places.list.indices) Places.list[place].second else customLat.toDoubleOrNull() ?: 23.8103
    fun lng(): Double = autoFix()?.second ?: if (place in Places.list.indices) Places.list[place].third else customLng.toDoubleOrNull() ?: 90.4125

    // উইজেটে দেখানোর স্থানের নাম: স্বয়ংক্রিয় অবস্থান হলে ৬০ কিমির মধ্যে সবচেয়ে কাছের শহর, নইলে বাছা স্থান
    fun placeName(): String {
        val f = autoFix()
        if (f == null) return if (place in Places.list.indices) Places.list[place].first else "নির্বাচিত স্থান"
        var best = -1
        var bd = Float.MAX_VALUE
        val r = FloatArray(1)
        for (i in Places.list.indices) {
            android.location.Location.distanceBetween(f.first, f.second, Places.list[i].second, Places.list[i].third, r)
            if (r[0] < bd) { bd = r[0]; best = i }
        }
        return if (best >= 0 && bd <= 60000f) Places.list[best].first else "বর্তমান অবস্থান"
    }

    // হিজরি তারিখের সমন্বয়: ডিফল্ট −১ বাংলাদেশের (চাঁদ সৌদির একদিন পরে); স্বয়ংক্রিয় অবস্থান বাংলাদেশের বাইরে হলে সেই −১ বাদ (সৌদির মতো)
    fun hijriShift(): Int {
        val f = autoFix() ?: return hijriAdj
        return hijriAdj + if (inBangladesh(f.first, f.second)) 0 else 1
    }

    companion object {
        fun inBangladesh(lat: Double, lng: Double) = lat in 20.5..26.7 && lng in 87.9..92.8
    }
}
