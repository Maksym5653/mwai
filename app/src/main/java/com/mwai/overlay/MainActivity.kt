package com.mwai.overlay

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.mwai.overlay.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val OVERLAY_CODE = 1001
    // Pre-filled Gemini key
    private val DEFAULT_KEY = "AIzaSyCdSIIIT25FqueEJTU7FX0qOHwaZNFFsjQ"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Load saved key or use default
        val prefs = getSharedPreferences("mwai", MODE_PRIVATE)
        val savedKey = prefs.getString("api_key", DEFAULT_KEY) ?: DEFAULT_KEY
        if (prefs.getString("api_key", null) == null) {
            prefs.edit().putString("api_key", DEFAULT_KEY).apply()
        }
        binding.etApiKey.setText(savedKey)

        updateStatus()

        binding.btnLaunch.setOnClickListener {
            if (Settings.canDrawOverlays(this)) startOverlayService()
            else requestOverlayPermission()
        }

        binding.btnStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            updateStatus()
            Toast.makeText(this, "MW AI зупинено", Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveKey.setOnClickListener {
            val key = binding.etApiKey.text.toString().trim()
            if (key.isNotEmpty()) {
                prefs.edit().putString("api_key", key).apply()
                Toast.makeText(this, "✓ Ключ збережено", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Введи API ключ!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus() {
        val running = OverlayService.isRunning
        binding.tvStatus.text = if (running) "● Активно" else "○ Неактивно"
        binding.tvStatus.setTextColor(
            if (running) getColor(R.color.accent_green) else getColor(R.color.text_secondary)
        )
        binding.btnLaunch.text = if (running) "Перезапустити" else "Запустити MW AI"
    }

    private fun startOverlayService() {
        val key = getSharedPreferences("mwai", MODE_PRIVATE).getString("api_key", "")
        if (key.isNullOrEmpty()) {
            Toast.makeText(this, "Спочатку збережи API ключ!", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, ScreenCaptureActivity::class.java))
        Toast.makeText(this, "MW AI запускається...", Toast.LENGTH_SHORT).show()
    }

    private fun requestOverlayPermission() {
        Toast.makeText(this, "Дозволь відображення поверх інших додатків", Toast.LENGTH_LONG).show()
        startActivityForResult(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")),
            OVERLAY_CODE
        )
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == OVERLAY_CODE) {
            if (Settings.canDrawOverlays(this)) startOverlayService()
            else Toast.makeText(this, "Дозвіл не надано :(", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() { super.onResume(); updateStatus() }
}
