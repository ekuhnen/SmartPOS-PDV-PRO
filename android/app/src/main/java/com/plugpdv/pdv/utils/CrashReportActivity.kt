package com.plugpdv.pdv.utils

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.plugpdv.pdv.R

class CrashReportActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val errorInfo = intent.getStringExtra("ERROR_INFO") ?: "No error info"
        
        val textView = TextView(this).apply {
            text = "O aplicativo parou de funcionar.\n\nERRO:\n$errorInfo"
            setPadding(32, 32, 32, 32)
            setTextIsSelectable(true)
        }
        setContentView(textView)
    }
}
