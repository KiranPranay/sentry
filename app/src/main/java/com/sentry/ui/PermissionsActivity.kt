package com.sentry.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.sentry.R

class PermissionsActivity : Activity() {

    private val PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.RECORD_AUDIO
    )
    
    // Android 13+ Notification permission
    // Manifest.permission.POST_NOTIFICATIONS 

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)

        val statusText = findViewById<TextView>(R.id.perm_status)
        val grantBtn = findViewById<Button>(R.id.btn_grant)

        grantBtn.setOnClickListener {
            // 1. Request Runtime Permissions
            ActivityCompat.requestPermissions(this, PERMISSIONS, 101)
            
            // 2. Request Exact Alarm Permission (Android 12+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                if (!alarmManager.canScheduleExactAlarms()) {
                    val intent = Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                        data = android.net.Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkStatus(findViewById(R.id.perm_status))
    }

    private fun checkStatus(textView: TextView) {
        val missing = PERMISSIONS.filter { 
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED 
        }.toMutableList()
        
        // Check Exact Alarm
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                missing.add("SCHEDULE_EXACT_ALARM")
            }
        }

        if (missing.isEmpty()) {
            textView.text = "All Permissions Granted!"
            // Optional: Finish automatically if all granted? 
            // Better to let user see "Granted" then close or use back.
        } else {
            textView.text = "Missing:\n" + missing.joinToString("\n")
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        checkStatus(findViewById(R.id.perm_status))
    }
}
