package app.aether

import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import app.aether.ui.AetherRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        val container = (application as AetherApp).container
        container.playback.connect()
        // The boot film plays on a cold start only: not when the activity is recreated.
        val coldStart = savedInstanceState == null && !booted
        booted = true
        val systemReducesMotion = SystemSettings.Global.getFloat(contentResolver, SystemSettings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        // Debug builds only: `--es aether.preview welcome` shows the welcome again without touching
        // the listener's settings, so it can be looked at on a phone that has been through it.
        val previewWelcome = packageName.endsWith(".debug") && intent?.getStringExtra("aether.preview") == "welcome"
        setContent { AetherRoot(container, playBoot = coldStart, systemReducesMotion = systemReducesMotion, previewWelcome = previewWelcome) }
        // Every screen paints its own black from its first frame; the window's black beneath it was
        // one more full screen of paint on every frame. It stays for the splash, then goes.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    private companion object {
        /** Per process: coming back to a running app never replays the boot. */
        var booted = false
    }
}
