package io.github.astromg01.monetizei

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import io.github.astromg01.monetizei.data.LocalRewardRepository
import io.github.astromg01.monetizei.game.GameSurfaceView
import io.github.astromg01.monetizei.telemetry.TelemetryRecorder

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterImmersiveMode()

        val rewardRepository = LocalRewardRepository(applicationContext)
        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        val telemetryRecorder = TelemetryRecorder(applicationContext, appVersion).also {
            it.initialize()
        }

        setContentView(GameSurfaceView(this, rewardRepository, telemetryRecorder))
    }

    @Suppress("DEPRECATION")
    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }
}
