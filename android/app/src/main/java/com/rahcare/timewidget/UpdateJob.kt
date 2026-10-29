package com.rahcare.timewidget

import android.app.job.JobParameters
import android.app.job.JobService

// ফোন নিজে নিজে (নেট থাকলে) চালায়: নতুন সংস্করণ খোঁজে এবং থাকলে নামিয়ে ইনস্টল শুরু করে
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Thread {
            try {
                Updater.autoUpdate(applicationContext)
            } finally {
                jobFinished(p, false)
            }
        }.start()
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean = true
}
