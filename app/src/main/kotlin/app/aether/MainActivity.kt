package app.aether

import android.os.Bundle
import android.provider.Settings as SystemSettings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import app.aether.ui.AetherRoot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var updateCheck: Job? = null

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
        // the listener's settings, so it can be looked at on a phone that has been through it;
        // `--es aether.preview update` looks for an update as if this were an old version.
        val debug = packageName.endsWith(".debug")
        val preview = intent?.getStringExtra("aether.preview")
        val previewWelcome = debug && preview == "welcome"
        if (debug && preview == "update") container.updates.checkNow(pretend = "0.0.1")
        setContent { AetherRoot(container, playBoot = coldStart, systemReducesMotion = systemReducesMotion, previewWelcome = previewWelcome) }
        // Full screen (PODIUM's D-85): Android's bars hidden while the setting is on; a swipe from the
        // edge shows them for a moment. The pages keep the room the bars take, so nothing moves under
        // the camera or jumps when they come and go.
        lifecycleScope.launch { container.settings.fullScreen.collect(::applyFullScreen) }
        // Every screen paints its own black from its first frame; the window's black beneath it was
        // one more full screen of paint on every frame. It stays for the splash, then goes.
        window.decorView.post { window.setBackgroundDrawable(null) }
    }

    // Some phones bring the bars back when the window has its focus again (a dialog, the shade): hidden again.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyFullScreen((application as AetherApp).container.settings.fullScreen.value)
    }

    private fun applyFullScreen(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = WindowInsetsCompat.Type.systemBars()
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(bars)
        } else {
            controller.show(bars)
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Debug builds: the update preview works on a running app too.
        if (packageName.endsWith(".debug") && intent.getStringExtra("aether.preview") == "update") {
            (application as AetherApp).container.updates.checkNow(pretend = "0.0.1")
        }
    }

    override fun onStart() {
        super.onStart()
        // A quiet look for a newer AETHER, once the opening has played and the room has settled.
        val updates = (application as AetherApp).container.updates
        updateCheck?.cancel()
        updateCheck = lifecycleScope.launch {
            delay(UpdateCheckAfterMs)
            updates.autoCheck()
        }
    }

    override fun onResume() {
        super.onResume()
        // Back from Android's page that lets AETHER install updates: carry on with the one waiting.
        (application as AetherApp).container.updates.resumed()
    }

    override fun onStop() {
        updateCheck?.cancel()
        super.onStop()
    }

    private companion object {
        /** Per process: coming back to a running app never replays the boot. */
        var booted = false

        /** Long enough for the boot film and the first room to have the screen to themselves. */
        const val UpdateCheckAfterMs = 6_000L
    }
}
