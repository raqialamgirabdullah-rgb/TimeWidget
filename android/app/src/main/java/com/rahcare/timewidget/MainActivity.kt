package com.rahcare.timewidget

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.location.Location
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// সেটিংস স্ক্রিন: উইজেট বসানো, স্থান, হিসাবের পদ্ধতি, তারিখ/সময়ের সমন্বয়, আপডেট
class MainActivity : Activity() {
    private lateinit var cfg: Cfg
    private lateinit var lockBtn: TextView
    private lateinit var preview: TextView
    private lateinit var updBtn: TextView
    private lateinit var customBox: LinearLayout
    private lateinit var locSpin: Spinner
    private lateinit var locStatus: TextView
    private lateinit var locBtn: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val updPoll = Runnable { checkUpdate() }
    private var update: Updater.Info? = null
    private var updBusy = false
    private var pendingInstall = false

    private val accent = Color.parseColor("#2BB673")
    private val cardBg = Color.parseColor("#151C24")
    private val muted = Color.parseColor("#9AA7B2")

    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            checkUpdate()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rr(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun text(s: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(parent: LinearLayout, title: String): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        c.background = rr(cardBg, 16)
        c.setPadding(dp(16), dp(14), dp(16), dp(14))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(12)
        parent.addView(c, lp)
        if (title.isNotEmpty()) {
            val t = text(title, 15f, accent, true)
            c.addView(t)
        }
        return c
    }

    private fun button(label: String, bg: Int = accent, fg: Int = Color.parseColor("#06201C"), onClick: () -> Unit): TextView {
        val b = text(label, 16f, fg, true)
        b.gravity = Gravity.CENTER
        b.background = rr(bg, 14)
        b.setPadding(dp(12), dp(14), dp(12), dp(14))
        b.isClickable = true
        b.setOnClickListener { onClick() }
        return b
    }

    // − মান + : একটি পূর্ণসংখ্যা বাড়ানো-কমানোর সারি
    private fun stepper(parent: LinearLayout, title: String, get: () -> Int, set: (Int) -> Unit, min: Int, max: Int, step: Int, show: (Int) -> String) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        parent.addView(row, lp)

        val label = text(title, 15f)
        row.addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val value = text(show(get()), 15f, Color.WHITE, true)
        value.gravity = Gravity.CENTER
        fun sq(sym: String, d: Int): TextView {
            val b = text(sym, 20f, Color.WHITE, true)
            b.gravity = Gravity.CENTER
            b.background = rr(Color.parseColor("#26323D"), 10)
            b.isClickable = true
            b.setOnClickListener {
                val n = (get() + d).coerceIn(min, max)
                set(n)
                value.text = show(n)
                changed()
            }
            return b
        }
        row.addView(sq("−", -step), LinearLayout.LayoutParams(dp(44), dp(40)))
        row.addView(value, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(sq("+", step), LinearLayout.LayoutParams(dp(44), dp(40)))
    }

    private fun signed(n: Int) = if (n > 0) "+" + Bn.num(n) else if (n < 0) "−" + Bn.num(-n) else Bn.num(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cfg = Cfg(this)

        val scroll = ScrollView(this)
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(16), dp(24), dp(16), dp(32))
        scroll.addView(col)
        scroll.setBackgroundColor(Color.parseColor("#0B0F14"))

        col.addView(text("টাইম উইজেট", 26f, Color.WHITE, true))
        col.addView(text("সময়, ইংরেজি-আরবি-বাংলা তারিখ ও পাঁচ ওয়াক্ত নামাজ — সব একটি উইজেটে। উইজেট ইন্টারনেট ছাড়াই চলে।", 14f, muted).apply { setPadding(0, dp(4), 0, 0) })

        // আপডেট (নতুন সংস্করণ থাকলেই দেখায়)
        updBtn = button("নতুন সংস্করণ এসেছে — আপডেট করুন", Color.parseColor("#FFB703"), Color.parseColor("#1A1200")) {
            val u = update
            if (u != null && !updBusy) install(u)
        }
        updBtn.visibility = View.GONE
        col.addView(updBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })

        // উইজেট বসান
        val c0 = card(col, "উইজেট বসান")
        c0.addView(text("এক ট্যাপে হোম স্ক্রিনে বসাতে নিচের বোতাম চাপুন। না হলে হোম স্ক্রিনের ফাঁকা জায়গায় চেপে ধরে → উইজেট → “টাইম উইজেট” বেছে নিন।", 13f, muted).apply { setPadding(0, dp(6), 0, dp(10)) })
        c0.addView(button("হোম স্ক্রিনে উইজেট বসান") {
            val mgr = AppWidgetManager.getInstance(this)
            if (mgr.isRequestPinAppWidgetSupported) {
                mgr.requestPinAppWidget(ComponentName(this, ClockWidget::class.java), null, null)
            } else {
                Toast.makeText(this, "হোম স্ক্রিনে চেপে ধরে → উইজেট → “টাইম উইজেট” বেছে নিন", Toast.LENGTH_LONG).show()
            }
        })
        c0.addView(text("লক স্ক্রিনে বসাতে নিচের বোতাম ফোনের লক স্ক্রিন সেটিংস খুলবে; সেখানে “উইজেট” থেকে “টাইম উইজেট” বেছে নিন। (সব ফোনে এই সুবিধা নেই)", 13f, muted).apply { setPadding(0, dp(14), 0, dp(10)) })
        c0.addView(button("লক স্ক্রিনে উইজেট বসান") {
            val tries = listOf(
                Intent("android.settings.LOCK_SCREEN_SETTINGS"),
                Intent(Settings.ACTION_DISPLAY_SETTINGS),
                Intent(Settings.ACTION_SETTINGS)
            )
            var opened = false
            for (t in tries) {
                try { startActivity(t); opened = true; break } catch (e: Exception) { }
            }
            Toast.makeText(
                this,
                if (opened) "লক স্ক্রিন সেটিংসে “উইজেট” খুঁজে “টাইম উইজেট” বেছে নিন। না পেলে এই ফোনে লক স্ক্রিন উইজেটের সুবিধা নেই।"
                else "ফোনের সেটিংস খোলা গেল না — সেটিংস → লক স্ক্রিন → উইজেট দেখুন",
                Toast.LENGTH_LONG
            ).show()
        })

        // লক স্ক্রিনে নোটিফিকেশন হিসেবে (সব ফোনে কাজ করে)
        val cl = card(col, "লক স্ক্রিনে দেখান")
        cl.addView(text("চালু করলে হোম স্ক্রিনের এই উইজেটটাই লক স্ক্রিনের একটা নোটিফিকেশনে থাকবে। নোটিফিকেশনটা নিচে টেনে বা ট্যাপ করে বড় করলে পুরো উইজেট দেখা যাবে। ছোট অবস্থায় শুধু সূর্যোদয়/সূর্যাস্ত ও তারিখ দেখায়।", 13f, muted).apply { setPadding(0, dp(6), 0, dp(10)) })
        lockBtn = button(if (cfg.lockCard) "লক স্ক্রিনের উইজেট বন্ধ করুন" else "লক স্ক্রিনে উইজেট চালু করুন") {
            if (cfg.lockCard) {
                cfg.lockCard = false
                lockBtn.text = "লক স্ক্রিনে উইজেট চালু করুন"
                ClockWidget.refresh(applicationContext)
            } else if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8)
            } else {
                cfg.lockCard = true
                lockBtn.text = "লক স্ক্রিনের উইজেট বন্ধ করুন"
                ClockWidget.refresh(applicationContext)
            }
        }
        cl.addView(lockBtn)

        // আজকের হিসাব
        val c1 = card(col, "আজকের হিসাব (উইজেটে যা দেখাবে)")
        preview = text("", 14f, Color.WHITE)
        preview.setPadding(0, dp(8), 0, 0)
        preview.setLineSpacing(0f, 1.25f)
        c1.addView(preview)

        // স্থান
        val c2 = card(col, "স্থান (নামাজের সময়ের জন্য)")
        // স্বয়ংক্রিয়: ফোনের অবস্থান ধরে এলাকা বদলালে নামাজের সময়, তারিখ ইত্যাদি নিজে বদলায়
        val autoRow = LinearLayout(this)
        autoRow.orientation = LinearLayout.HORIZONTAL
        autoRow.gravity = Gravity.CENTER_VERTICAL
        autoRow.addView(text("ফোনের অবস্থান থেকে স্বয়ংক্রিয়", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val autoSw = Switch(this)
        autoSw.isChecked = cfg.auto
        autoRow.addView(autoSw)
        c2.addView(autoRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        locStatus = text("", 12f, muted)
        locStatus.setPadding(0, dp(6), 0, 0)
        c2.addView(locStatus)
        locBtn = button("অবস্থানের অনুমতি দিন") { askLocation() }
        c2.addView(locBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        c2.addView(text("টাইমজোন বদলালে ঘড়ি নিজে বদলাতে ফোনের “স্বয়ংক্রিয় তারিখ ও সময় / টাইমজোন” চালু রাখুন ›", 12f, accent).apply {
            setPadding(0, dp(10), 0, 0)
            isClickable = true
            setOnClickListener { try { startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) } catch (e: Exception) {} }
        })
        autoSw.setOnCheckedChangeListener { _, on ->
            cfg.auto = on
            if (on) {
                LocationUpdater.sync(this)
                askLocation()
            }
            LocationUpdater.register(applicationContext)
            changed()
            locUi()
        }
        val spin = Spinner(this)
        locSpin = spin
        spin.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Places.names())
        spin.setSelection(cfg.place.coerceIn(0, Places.CUSTOM))
        c2.addView(spin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        customBox = LinearLayout(this)
        customBox.orientation = LinearLayout.VERTICAL
        fun field(hint: String, get: () -> String, set: (String) -> Unit) {
            val e = EditText(this)
            e.hint = hint
            e.setText(get())
            e.setTextColor(Color.WHITE)
            e.setHintTextColor(muted)
            e.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
            e.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    set(s?.toString() ?: "")
                    changed()
                }
            })
            customBox.addView(e)
        }
        field("অক্ষাংশ (যেমন 23.8103)", { cfg.customLat }, { cfg.customLat = it })
        field("দ্রাঘিমাংশ (যেমন 90.4125)", { cfg.customLng }, { cfg.customLng = it })
        c2.addView(customBox)
        customBox.visibility = if (cfg.place >= Places.CUSTOM) View.VISIBLE else View.GONE
        spin.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (cfg.place != pos) {
                    cfg.place = pos
                    changed()
                }
                locUi()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        // আসরের হিসাব
        val c3 = card(col, "আসরের হিসাব")
        val rg = RadioGroup(this)
        rg.orientation = RadioGroup.VERTICAL
        val rh = RadioButton(this).apply { text = "হানাফি (ছায়া দ্বিগুণ) — বাংলাদেশে প্রচলিত"; setTextColor(Color.WHITE); id = 1 }
        val rs = RadioButton(this).apply { text = "শাফেয়ি/মালিকি/হাম্বলি (ছায়া সমান)"; setTextColor(Color.WHITE); id = 2 }
        rg.addView(rh); rg.addView(rs)
        rg.check(if (cfg.hanafi) 1 else 2)
        rg.setOnCheckedChangeListener { _, id -> cfg.hanafi = (id == 1); changed() }
        c3.addView(rg, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        c3.addView(text("ফজর ও ইশা: সূর্য দিগন্তের ১৮° নিচে (করাচি/বিশ্ব মুসলিম লীগ পদ্ধতি)", 12f, muted).apply { setPadding(0, dp(6), 0, 0) })

        // তারিখ সমন্বয়
        val c4 = card(col, "তারিখ সমন্বয় (দিন)")
        c4.addView(text("চাঁদ দেখার পার্থক্যের জন্য হিজরি তারিখ ১-২ দিন এগোতে-পেছাতে পারেন। বাংলাদেশে সাধারণত সৌদির একদিন পরে, তাই ডিফল্ট −১। স্বয়ংক্রিয় অবস্থান বাংলাদেশের বাইরে গেলে এই −১ নিজে বাদ যায় (সৌদির মতো)।", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        stepper(c4, "আরবি (হিজরি) তারিখ", { cfg.hijriAdj }, { cfg.hijriAdj = it }, -3, 3, 1) { signed(it) }
        stepper(c4, "বাংলা তারিখ", { cfg.banglaAdj }, { cfg.banglaAdj = it }, -3, 3, 1) { signed(it) }

        // নামাজের সময় সমন্বয়
        val c5 = card(col, "নামাজের সময় সমন্বয় (মিনিট)")
        c5.addView(text("আপনার মসজিদ বা প্রিয় পঞ্জিকার সাথে মেলাতে প্রতিটি ওয়াক্ত মিনিটে এগোতে-পেছাতে পারেন।", 12f, muted).apply { setPadding(0, dp(4), 0, 0) })
        for (i in 0..4) stepper(c5, ClockWidget.NAMES[i], { cfg.off(i) }, { cfg.setOff(i, it) }, -30, 30, 1) { signed(it) }

        // দেখানো
        val c6 = card(col, "দেখানো")
        stepper(c6, "লেখার আকার", { cfg.scale }, { cfg.scale = it }, 70, 200, 10) { Bn.num(it) + "%" }
        val rd = RadioGroup(this)
        rd.orientation = RadioGroup.VERTICAL
        val d1 = RadioButton(this).apply { text = "নামাজের সময় বাংলা অঙ্কে (৪:৪৬)"; setTextColor(Color.WHITE); id = 11 }
        val d2 = RadioButton(this).apply { text = "নামাজের সময় ইংরেজি অঙ্কে (4:46)"; setTextColor(Color.WHITE); id = 12 }
        rd.addView(d1); rd.addView(d2)
        rd.check(if (cfg.bnDigits) 11 else 12)
        rd.setOnCheckedChangeListener { _, id -> cfg.bnDigits = (id == 11); changed() }
        c6.addView(rd, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })

        @Suppress("DEPRECATION")
        val ver = packageManager.getPackageInfo(packageName, 0).versionName
        col.addView(text("সংস্করণ $ver", 12f, muted).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, 0) })

        setContentView(scroll)
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netCallback)
        } catch (e: Exception) {
        }
        locUi()
        updatePreview()
    }

    // অবস্থানের অনুমতি: প্রথমে আনুমানিক অবস্থান, তারপর অ্যাপ বন্ধ থাকলেও এলাকা বদলানো ধরতে "সব সময়"
    private fun askLocation() {
        if (!LocationUpdater.hasPerm(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 31)
        } else if (!LocationUpdater.hasBg(this)) {
            Toast.makeText(this, "পরের পর্দায় “সব সময় অনুমতি দিন” বেছে নিন — তাহলে অ্যাপ বন্ধ থাকলেও এলাকা বদলালে উইজেট নিজে বদলাবে", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 32)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 8) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            cfg.lockCard = ok
            lockBtn.text = if (ok) "লক স্ক্রিনের উইজেট বন্ধ করুন" else "লক স্ক্রিনে উইজেট চালু করুন"
            if (!ok) Toast.makeText(this, "নোটিফিকেশনের অনুমতি না দিলে লক স্ক্রিনে দেখানো যাবে না", Toast.LENGTH_LONG).show()
            ClockWidget.refresh(applicationContext)
            return
        }
        if (requestCode == 31 && LocationUpdater.hasPerm(this)) {
            LocationUpdater.sync(this)
            LocationUpdater.register(applicationContext)
            LocationUpdater.fresh(applicationContext) { runOnUiThread { changed(); locUi() } }
            changed()
            askLocation() // এবার "সব সময়" ধাপ
        } else if (requestCode == 32) {
            LocationUpdater.register(applicationContext)
        }
        locUi()
    }

    private fun nearestPlace(lat: Double, lng: Double): String {
        var bestName = ""
        var best = Float.MAX_VALUE
        val r = FloatArray(1)
        for (p in Places.list) {
            Location.distanceBetween(lat, lng, p.second, p.third, r)
            if (r[0] < best) { best = r[0]; bestName = p.first }
        }
        val pos = Bn.digits(String.format(Locale.US, "%.2f, %.2f", lat, lng))
        return if (best < 60000f) "$bestName এলাকা ($pos)" else "অন্য এলাকা ($pos)"
    }

    // স্থানের কার্ড: সুইচ চালু ও অবস্থান পাওয়া গেলে হাতে বাছার ঘর লুকায়; অনুমতি বাকি থাকলে বোতাম দেখায়
    private fun locUi() {
        val a = cfg.auto
        val fix = cfg.autoFix()
        val perm = LocationUpdater.hasPerm(this)
        val useAuto = a && fix != null
        locSpin.visibility = if (useAuto) View.GONE else View.VISIBLE
        customBox.visibility = if (!useAuto && cfg.place >= Places.CUSTOM) View.VISIBLE else View.GONE
        locBtn.visibility = if (a && (!perm || !LocationUpdater.hasBg(this))) View.VISIBLE else View.GONE
        locBtn.text = if (!perm) "অবস্থানের অনুমতি দিন" else "“সব সময়” অনুমতি দিন (অ্যাপ বন্ধ থাকলেও বদলাবে)"
        locStatus.text = when {
            !a -> "বন্ধ — নিচে হাতে স্থান বেছে নিন"
            !perm -> "অনুমতি দেওয়া হয়নি — আপাতত নিচের স্থান ব্যবহার হচ্ছে"
            fix == null -> "অবস্থান খোঁজা হচ্ছে… (ফোনের লোকেশন চালু আছে কি না দেখুন) — আপাতত নিচের স্থান ব্যবহার হচ্ছে"
            else -> "এখন: " + nearestPlace(fix.first, fix.second) + "\nসর্বশেষ বদল: " + Bn.digits(SimpleDateFormat("d MMM, HH:mm", Locale.US).format(Date(cfg.autoAt))) +
                (if (LocationUpdater.hasBg(this)) "" else "\n(অ্যাপ বন্ধ থাকলে এলাকা বদল ধরা পড়বে না — “সব সময়” অনুমতি দিন)")
        }
    }

    // কোনো সেটিং বদলালে উইজেট ও প্রিভিউ নতুন করে আঁকে
    private fun changed() {
        ClockWidget.refresh(applicationContext)
        updatePreview()
    }

    private fun updatePreview() {
        val s = ClockWidget.compute(this)
        val sb = StringBuilder()
        sb.append("আরবি: ").append(s.hijri).append("\n")
        sb.append("বাংলা: ").append(s.bangla).append("\n")
        for (i in 0..4) {
            sb.append(ClockWidget.NAMES[i]).append("  ").append(Prayer.fmt(s.min[i], cfg.bnDigits))
            if (i == s.cur) sb.append("   ◀ চলছে")
            if (i < 4) sb.append("\n")
        }
        preview.text = sb.toString()
    }

    override fun onResume() {
        super.onResume()
        // “অজানা সোর্স” অনুমতি দিয়ে ফিরলে আপডেট নিজেই শুরু হয়
        val u = update
        if (pendingInstall && u != null && packageManager.canRequestPackageInstalls()) {
            pendingInstall = false
            install(u)
        }
        update = Updater.saved(this)
        refreshUpd()
        checkUpdate()
        LocationUpdater.sync(this)
        LocationUpdater.register(applicationContext)
        LocationUpdater.fresh(applicationContext) { runOnUiThread { changed(); locUi() } }
        locUi()
        updatePreview()
        ClockWidget.refresh(applicationContext)
    }

    override fun onPause() {
        handler.removeCallbacks(updPoll)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netCallback)
        } catch (e: Exception) {
        }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ───────── আপডেট ─────────
    private fun refreshUpd() {
        if (update == null) {
            updBtn.visibility = View.GONE
            return
        }
        updBtn.visibility = View.VISIBLE
        if (!updBusy) {
            updBtn.text = if (packageManager.canRequestPackageInstalls()) "নতুন সংস্করণ এসেছে — আপডেট করুন" else "আপডেটের অনুমতি দিন"
        }
    }

    // অনলাইনে থাকলে নতুন সংস্করণ খোঁজে; অফলাইনে চুপচাপ ব্যর্থ হয়
    private fun checkUpdate() {
        val app = applicationContext
        Thread {
            val now = Updater.checkNow(app)
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                update = now
                refreshUpd()
                // নতুন সংস্করণ না পেলে অ্যাপ খোলা থাকা অবস্থায় প্রতি ৩০ সেকেন্ডে আবার দেখে
                handler.removeCallbacks(updPoll)
                if (now == null) handler.postDelayed(updPoll, 30000L)
            }
        }.start()
    }

    private fun install(u: Updater.Info) {
        if (!packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            Toast.makeText(this, "পরের পর্দায় “Allow from this source” চালু করে ফিরে আসুন — আপডেট নিজেই শুরু হবে", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        if (updBusy) return
        updBusy = true
        updBtn.text = "ডাউনলোড হচ্ছে… ০%"
        Thread {
            val inst = packageManager.packageInstaller
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
                            if (total > 0) {
                                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                                if (pct != last) {
                                    last = pct
                                    runOnUiThread { updBtn.text = "ডাউনলোড হচ্ছে… ${Bn.num(pct)}%" }
                                }
                            }
                        }
                        s.fsync(out)
                    }
                }
                val pi = PendingIntent.getBroadcast(
                    this, id, Intent(this, UpdateReceiver::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )
                runOnUiThread { updBtn.text = "ইনস্টল হচ্ছে…" }
                s.commit(pi.intentSender)
                // ইনস্টল সফল হলে অ্যাপ নিজেই নতুন হয়ে চালু হবে; না হলে ৬০ সেকেন্ড পর বোতাম ফিরিয়ে আনি
                handler.postDelayed({ updBusy = false; refreshUpd() }, 60000L)
            } catch (e: Exception) {
                try { inst.abandonSession(id) } catch (x: Exception) {}
                runOnUiThread {
                    updBusy = false
                    refreshUpd()
                    Toast.makeText(this, "আপডেট শুরু করা গেল না (${e.javaClass.simpleName}) — ইন্টারনেট দেখে আবার চেষ্টা করুন", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
