package com.rahcare.timewidget

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper

// ফোনের অবস্থান থেকে স্থান স্বয়ংক্রিয় বদলানো। শুধু আনুমানিক (শহর-স্তরের) অবস্থান লাগে, GPS নয়।
// ব্যাকগ্রাউন্ডে (অ্যাপ বন্ধ থাকলেও) এলাকা বদলানো ধরতে "সব সময়" অনুমতি লাগে; না থাকলে অ্যাপ খুললে বা উইজেট নতুন হওয়ার সময় যা জানা আছে তা-ই ধরে।
object LocationUpdater {
    const val ACTION_LOC = "com.rahcare.timewidget.LOCATION"
    private const val MOVE_M = 3000f // এর বেশি সরলে স্থান বদলায় (ছোট নড়াচড়ায় নয়)

    fun hasPerm(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    // Android ১০+ এ ব্যাকগ্রাউন্ডে অবস্থান পেতে আলাদা "সব সময়" অনুমতি লাগে
    fun hasBg(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 29 || ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    // নতুন অবস্থান রাখে; আগেরটার থেকে ৩ কিমির বেশি সরলে (বা প্রথমবার) true
    fun save(ctx: Context, l: Location): Boolean {
        val c = Cfg(ctx)
        val oldLat = c.autoLat.toDoubleOrNull()
        val oldLng = c.autoLng.toDoubleOrNull()
        val moved = if (oldLat == null || oldLng == null) true else {
            val r = FloatArray(1)
            Location.distanceBetween(oldLat, oldLng, l.latitude, l.longitude, r)
            r[0] > MOVE_M
        }
        if (moved) {
            c.autoLat = l.latitude.toString()
            c.autoLng = l.longitude.toString()
            c.autoAt = System.currentTimeMillis()
        }
        return moved
    }

    // ফোনে জমা থাকা সবচেয়ে নতুন অবস্থান নেয় (নতুন করে খোঁজে না, তাই ব্যাটারি লাগে না); স্থান বদলালে true
    fun sync(ctx: Context): Boolean {
        val c = Cfg(ctx)
        if (!c.auto || !hasPerm(ctx)) return false
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            var best: Location? = null
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
                try {
                    val l = lm.getLastKnownLocation(p)
                    if (l != null && (best == null || l.time > best!!.time)) best = l
                } catch (e: Exception) {
                }
            }
            val b = best
            if (b != null) save(ctx, b) else false
        } catch (e: Exception) {
            false
        }
    }

    // অ্যাপ খোলা থাকলে একবার নতুন অবস্থান চায় (হাল-নাগাদ করতে); স্থান বদলালে done() ডাকে
    @Suppress("DEPRECATION")
    fun fresh(ctx: Context, done: () -> Unit) {
        if (!Cfg(ctx).auto || !hasPerm(ctx)) return
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val p = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) LocationManager.NETWORK_PROVIDER else return
            lm.requestSingleUpdate(p, object : LocationListener {
                override fun onLocationChanged(l: Location) { if (save(ctx, l)) done() }
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, Looper.getMainLooper())
        } catch (e: Exception) {
        }
    }

    private fun pending(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 77, Intent(ctx, LocationReceiver::class.java).setAction(ACTION_LOC),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    // ব্যাকগ্রাউন্ডে এলাকা বদলানো ধরার অনুরোধ (১৫ মিনিট ও ২ কিমি পরপর; "সব সময়" অনুমতি থাকলে কাজ করে)
    fun register(ctx: Context) {
        try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val pi = pending(ctx)
            if (!Cfg(ctx).auto || !hasPerm(ctx)) { lm.removeUpdates(pi); return }
            lm.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 0L, 2000f, pi)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 15 * 60 * 1000L, 2000f, pi)
            }
        } catch (e: Exception) {
        }
    }
}

class LocationReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(ctx: Context, intent: Intent) {
        try {
            val l = intent.getParcelableExtra<Location>(LocationManager.KEY_LOCATION_CHANGED) ?: return
            if (LocationUpdater.save(ctx, l)) ClockWidget.refresh(ctx)
        } catch (e: Exception) {
        }
    }
}
