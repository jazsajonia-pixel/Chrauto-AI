package com.chrauto.ai.accessibility

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class AccessibilityInteractionTestActivity : Activity() {
    lateinit var input: EditText
    lateinit var tapTarget: Button
    lateinit var result: TextView
    lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        input = EditText(this).apply { hint = "Search text"; contentDescription = "Search text" }
        tapTarget = Button(this).apply { text = "Tap target"; contentDescription = "Tap target" }
        result = TextView(this).apply { text = "Not tapped" }
        tapTarget.setOnClickListener { result.text = "Tapped" }
        scroll = ScrollView(this).apply {
            val longText = TextView(this@AccessibilityInteractionTestActivity).apply {
                text = (1..30).joinToString("\n") { "Scrollable row $it" }
                contentDescription = "Scrollable content"
            }
            addView(longText, ViewGroup.LayoutParams(-1, 900))
        }
        root.addView(input, LinearLayout.LayoutParams(-1, 120))
        root.addView(tapTarget, LinearLayout.LayoutParams(-1, 120))
        root.addView(result, LinearLayout.LayoutParams(-1, 100))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 500))
        setContentView(root)
    }
}
