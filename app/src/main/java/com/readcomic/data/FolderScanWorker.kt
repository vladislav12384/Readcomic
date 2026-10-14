package com.readcomic.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Looks through the watched folders now and then, even while the app is closed. */
class FolderScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LibraryRepository.get(applicationContext).scanAll()
        return Result.success()
    }

    companion object {
        private const val NAME = "watched-folders"

        /** Runs every [interval] (null or "at start" only: no background scans). */
        fun schedule(context: Context, interval: ScanInterval?) {
            val work = WorkManager.getInstance(context)
            if (interval == null || interval.hours == 0L) {
                work.cancelUniqueWork(NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<FolderScanWorker>(interval.hours, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            work.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
