package com.plugpdv.pdv.utils

import android.os.Bundle
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.plugpdv.pdv.R

class CrashReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val errorInfo = intent.getStringExtra("ERROR_INFO") ?: "No error info"

        val basePadding = 32
        val textView = TextView(this).apply {
            text = "O aplicativo parou de funcionar.\n\nERRO:\n$errorInfo"
            setPadding(basePadding, basePadding, basePadding, basePadding)
            setTextIsSelectable(true)
        }
        setContentView(textView)

        ViewCompat.setOnApplyWindowInsetsListener(textView) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                basePadding + safeArea.left,
                basePadding + safeArea.top,
                basePadding + safeArea.right,
                basePadding + safeArea.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(textView)
    }
}
