package com.akuras.pdfscanner

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log

class UpdateCleanupReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val deleted = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?.resolve(UPDATE_APK_NAME)
            ?.delete()
        if (deleted != true) {
            Log.w("UpdateCleanupReceiver", "Failed to delete downloaded update APK")
        }
    }
}
