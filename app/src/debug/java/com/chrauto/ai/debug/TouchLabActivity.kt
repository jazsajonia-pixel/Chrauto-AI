package com.chrauto.ai.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only screen used by [SelfTestReceiver] to verify taps, typing, and Enter on a real device. */
class TouchLabActivity : Activity() {
    companion object { @Volatile var instance: TouchLabActivity? = null }
    lateinit var status: TextView
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        status = TextView(this).apply { text = "Idle"; textSize = 18f }
        val input = EditText(this).apply {
            hint = "Search lab"; isSingleLine = true; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { v, _, _ -> status.text = "Submitted: ${v.text}"; true }
        }
        // A clickable row whose label is a non-clickable child: the AI usually picks the label.
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; isClickable = true; setPadding(40, 40, 40, 40)
            setBackgroundColor(0xFFEDE8FF.toInt())
            addView(TextView(this@TouchLabActivity).apply { text = "Open row label"; textSize = 18f })
            setOnClickListener { status.text = "Row tapped" }
        }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 120, 32, 32) }
        list.addView(status); list.addView(input); list.addView(row)
        val corner = Button(this).apply { text = "Corner button"; setOnClickListener { status.text = "Corner tapped" } }
        setContentView(FrameLayout(this).apply {
            addView(list)
            addView(corner, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, 40, 120) })
        })
    }

    /** Shifts everything down, making previously observed node paths stale. */
    fun insertBanner() { list.addView(TextView(this).apply { text = "New banner appeared"; textSize = 22f; setPadding(0, 60, 0, 60) }, 0) }

    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
}
