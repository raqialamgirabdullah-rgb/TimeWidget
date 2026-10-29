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

    // ফজর, যোহর, আসর, মাগরিব, ইশা — মিনিটের সমন্বয়। ডিফল্ট: যোহর +১, মাগরিব +৩ (সূর্যাস্তের পর সতর্কতার মিনিট)
    private val defOff = intArrayOf(0, 1, 0, 3, 0)
    fun off(i: Int): Int = sp.getInt("off$i", defOff[i])
    fun setOff(i: Int, v: Int) = sp.edit().putInt("off$i", v).apply()

    fun lat(): Double = if (place in Places.list.indices) Places.list[place].second else customLat.toDoubleOrNull() ?: 23.8103
    fun lng(): Double = if (place in Places.list.indices) Places.list[place].third else customLng.toDoubleOrNull() ?: 90.4125
}
