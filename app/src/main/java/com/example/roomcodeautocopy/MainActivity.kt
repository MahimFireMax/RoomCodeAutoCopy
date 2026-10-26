package com.example.roomcodeautocopy

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val requestCapture = 501
    private lateinit var xField: EditText
    private lateinit var yField: EditText
    private lateinit var wField: EditText
    private lateinit var hField: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("region", Context.MODE_PRIVATE)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(24, 28, 24, 24)
        }
        root.addView(TextView(this).apply {
            text = "Room Code Auto Copy"
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = "Set the scan region as percentages of the screen (0–100). Example: x=20, y=30, width=60, height=25."
            textSize = 15f
            setPadding(0, 12, 0, 16)
        })

        xField = field("Left X %", prefs.getInt("x", 10).toString())
        yField = field("Top Y %", prefs.getInt("y", 10).toString())
        wField = field("Width %", prefs.getInt("w", 80).toString())
        hField = field("Height %", prefs.getInt("h", 80).toString())
        listOf(xField, yField, wField, hField).forEach { root.addView(it) }

        root.addView(Button(this).apply {
            text = "Save region"
            setOnClickListener {
                val x = xField.intOrNull(); val y = yField.intOrNull()
                val w = wField.intOrNull(); val h = hField.intOrNull()
                if (x == null || y == null || w == null || h == null ||
                    x < 0 || y < 0 || w < 1 || h < 1 || x + w > 100 || y + h > 100) {
                    Toast.makeText(this@MainActivity, "Enter valid percentages (0–100)", Toast.LENGTH_LONG).show()
                } else {
                    prefs.edit().putInt("x", x).putInt("y", y).putInt("w", w).putInt("h", h).apply()
                    Toast.makeText(this@MainActivity, "Region saved", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Start watching screen"
            setOnClickListener {
                val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                startActivityForResult(mgr.createScreenCaptureIntent(), requestCapture)
            }
        })
        root.addView(Button(this).apply {
            text = "Stop watching"
            setOnClickListener { stopService(Intent(this@MainActivity, CaptureService::class.java)) }
        })
        status = TextView(this).apply {
            text = "Note: Android shows a system capture notification while scanning."
            textSize = 14f
            setPadding(0, 18, 0, 0)
        }
        root.addView(status)
        setContentView(root)
    }

    private fun field(label: String, value: String): EditText =
        EditText(this).apply {
            hint = label
            setSingleLine(true)
            setText(value)
            textSize = 16f
        }

    private fun EditText.intOrNull(): Int? = text.toString().trim().toIntOrNull()

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
