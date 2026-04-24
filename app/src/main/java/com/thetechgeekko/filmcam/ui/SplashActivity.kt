package com.thetechgeekko.filmcam.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.thetechgeekko.filmcam.CameraActivity

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Ensure splash screen starts in portrait mode
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        // Launch main Film.cam activity
        startActivity(Intent(this, CameraActivity::class.java))
        finish()
    }
}
