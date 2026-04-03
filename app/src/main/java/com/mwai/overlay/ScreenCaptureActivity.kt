package com.mwai.overlay

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

class ScreenCaptureActivity : Activity() {

    private val REQUEST_CODE = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mgr = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mgr.createScreenCaptureIntent(), REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                putExtra("result_code", resultCode)
                putExtra("data", data)
            }
            startForegroundService(serviceIntent)
        }
        finish()
    }
}
