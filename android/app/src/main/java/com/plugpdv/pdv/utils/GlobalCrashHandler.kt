package com.plugpdv.pdv.utils

import android.content.Context
import android.content.Intent
import android.os.Process
import android.util.Log
import kotlin.system.exitProcess

class GlobalCrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val stackTrace = Log.getStackTraceString(throwable)
        Log.e("GlobalCrashHandler", "FATAL EXCEPTION: ${throwable.message}\n$stackTrace")

        // In a real scenario, we could start a special Activity to show the error
        // For now, let's at least try to log it or save it to a file the user can read
        try {
            context.openFileOutput("last_crash.log", Context.MODE_PRIVATE).use {
                it.write(stackTrace.toByteArray())
            }
        } catch (e: Exception) {
            // Ignore
        }

        // We can't show a dialog here because the app is dying
        // But we could start a new activity with the error text
        val intent = Intent(context, CrashReportActivity::class.java).apply {
            putExtra("ERROR_INFO", stackTrace)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        context.startActivity(intent)

        exitProcess(1)
    }

    companion object {
        fun init(context: Context) {
            Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(context))
        }
    }
}
