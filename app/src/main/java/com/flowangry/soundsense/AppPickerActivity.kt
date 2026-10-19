package com.flowangry.soundsense

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class AppPickerActivity : AppCompatActivity() {
    private val PREF_KEY = "selected_package"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_picker)

        val listView = findViewById<ListView>(R.id.app_list)
        val btnMicro = findViewById<Button>(R.id.btn_micro_only)
        val btnClear = findViewById<Button>(R.id.btn_clear_selection)

        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = apps.map { pm.getApplicationLabel(it).toString() + " (" + it.packageName + ")" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        listView.setOnItemClickListener { _, _, position, _ ->
            val app = apps[position]
            val pkg = app.packageName
            val prefs = getSharedPreferences("sound_dir_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY, pkg).apply()
            Toast.makeText(this, "Selected: ${pm.getApplicationLabel(app)}", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().putExtra("selected_package", pkg))
            finish()
        }

        btnMicro.setOnClickListener {
            val prefs = getSharedPreferences("sound_dir_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_KEY, "__MICROPHONE_ONLY__").apply()
            Toast.makeText(this, "Selected microphone-only mode", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent().putExtra("selected_package", "__MICROPHONE_ONLY__"))
            finish()
        }

        btnClear.setOnClickListener {
            val prefs = getSharedPreferences("sound_dir_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove(PREF_KEY).apply()
            Toast.makeText(this, "Cleared selection", Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK, Intent())
            finish()
        }
    }
}
