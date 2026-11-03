package com.customframe.lenovo

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView

class CrashActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tv = TextView(this).apply {
            setTextColor(Color.YELLOW)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(32, 32, 32, 32)
            text = "CRASH REPORT (photo this and send it):\n\n" + (intent.getStringExtra("trace") ?: "no trace")
        }
        val sv = ScrollView(this).apply { setBackgroundColor(Color.BLACK); addView(tv) }
        setContentView(sv)
    }
}