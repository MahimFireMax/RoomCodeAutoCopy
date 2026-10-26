package com.example.roomcodeautocopy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val requestCapture = 501
    private lateinit var xField: EditText
    private lateinit var yField: EditText
    private lateinit var wField: EditText
    private lateinit var hField: EditText
    private lateinit var modeSpinner: Spinner

    private val modeKeys = listOf("numbers", "letters", "mixed", "text")
    private val modeLabels = listOf(
        "Numbers — শুধু সংখ্যা",
        "Letters — শুধু অক্ষর",
        "Mixed Code — অক্ষর ও সংখ্যা",
        "Full Text — সম্পূর্ণ লেখা"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("region", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 24, 22, 24)
            setBackgroundColor(0xFFF4F6FA.toInt())
        }

        root.addView(TextView(this).apply {
            text = "Universal Screen Text & Code Auto Copier"
            textSize = 23f
            setTextColor(0xFF172554.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
        })

        root.addView(TextView(this).apply {
            text = "RECOGNITION MODE"
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setPadding(0, 12, 0, 4)
        })

        modeSpinner = Spinner(this)
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modeLabels
        )
        val savedMode = prefs.getString("mode", "mixed") ?: "mixed"
        modeSpinner.setSelection(modeKeys.indexOf(savedMode).coerceAtLeast(0))
        modeSpinner.onItemSelectedListener = object :
            android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: android.widget.AdapterView<*>?,
                view: android.view.View?,
                position: Int,
                id: Long
            ) {
                prefs.edit().putString("mode", modeKeys[position]).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        root.addView(modeSpinner)

        root.addView(TextView(this).apply {
            text = "SCAN REGION"
            textSize = 13f
            setTextColor(0xFF475569.toInt())
            setPadding(0, 18, 0, 4)
        })

        root.addView(TextView(this).apply {
            text = "Screen-এর যে অংশ স্ক্যান করতে চাও, তার অবস্থান ও আকার শতাংশে দাও (0–100)।"
            textSize = 14f
            setTextColor(0xFF334155.toInt())
            setPadding(0, 4, 0, 8)
        })

        xField = field("Left X %", prefs.getInt("x", 10).toString())
        yField = field("Top Y %", prefs.getInt("y", 10).toString())
        wField = field("Width %", prefs.getInt("w", 80).toString())
        hField = field("Height %", prefs.getInt("h", 80).toString())
        listOf(xField, yField, wField, hField).forEach { root.addView(it) }

        root.addView(button("Save Region") {
            val x = xField.intOrNull()
            val y = yField.intOrNull()
            val w = wField.intOrNull()
            val h = hField.intOrNull()
            if (x == null || y == null || w == null || h == null ||
                x < 0 || y < 0 || w < 1 || h < 1 ||
                x + w > 100 || y + h > 100
            ) {
                Toast.makeText(this, "Valid percentages দিন (0–100)", Toast.LENGTH_LONG).show()
            } else {
                prefs.edit().putInt("x", x).putInt("y", y)
                    .putInt("w", w).putInt("h", h).apply()
                Toast.makeText(this, "Region saved", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(button("Allow Floating Panel") {
            if (android.os.Build.VERSION.SDK_INT >= 23 &&
                !Settings.canDrawOverlays(this)) {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            } else {
                Toast.makeText(this, "Overlay permission already allowed", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(button("Start Screen Scanning") {
            val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
        })

        root.addView(button("Stop Scanning") {
            stopService(Intent(this, CaptureService::class.java))
            Toast.makeText(this, "Scanning stopped", Toast.LENGTH_SHORT).show()
        })

        root.addView(TextView(this).apply {
            text = "Android-এর screen capture notification স্ক্যান চলার সময় দেখা যাবে."
            textSize = 13f
            setTextColor(0xFF64748B.toInt())
            setPadding(0, 18, 0, 0)
        })

        val scroll = ScrollView(this).apply {
            addView(root)
        }
        setContentView(scroll)
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }

    private fun field(label: String, value: String): EditText =
        EditText(this).apply {
            hint = label
            setSingleLine(true)
            setText(value)
            textSize = 16f
        }

    private fun EditText.intOrNull(): Int? =
        text.toString().trim().toIntOrNull()

    @Deprecated("Deprecated by Android but kept for broad compatibility in this starter")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == requestCapture && resultCode == RESULT_OK && data != null) {
            val svc = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
            }
            startForegroundService(svc)
            Toast.makeText(this, "Screen scanning started", Toast.LENGTH_SHORT).show()
        }
    }
}
