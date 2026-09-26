package dev.harness.android

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.harness.android.ui.HarnessApp

class MainActivity : ComponentActivity() {
    private val model: HarnessViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model.openNotification(intent.getStringExtra("server"), intent.getStringExtra("sessionId"))
        enableEdgeToEdge()
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            HarnessTheme(fontScale = state.fontScale) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                DisposableEffect(lifecycle) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            Lifecycle.Event.ON_START -> model.resume()
                            Lifecycle.Event.ON_STOP -> model.pause()
                            else -> Unit
                        }
                    }
                    lifecycle.addObserver(observer)
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) model.resume()
                    onDispose { lifecycle.removeObserver(observer) }
                }
                HarnessApp(state, model)
            }
        }
    }
    override fun onStart() { NotificationMonitor.appVisible = true; super.onStart() }
    override fun onStop() { NotificationMonitor.appVisible = false; super.onStop() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        model.openNotification(intent.getStringExtra("server"), intent.getStringExtra("sessionId"))
    }
}

@Composable
fun HarnessTheme(fontScale: Float = 1f, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val scaledDensity = remember(density, fontScale) {
        if (fontScale == 1f) density else Density(density.density, density.fontScale * normalizedFontScale(fontScale))
    }
    val colors = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFFAEBFFF), onPrimary = Color(0xFF142970),
        primaryContainer = Color(0xFF263D89), secondary = Color(0xFF90D9CD),
        background = Color(0xFF10131C), surface = Color(0xFF151923),
        surfaceVariant = Color(0xFF222838), outlineVariant = Color(0xFF30384B),
    ) else lightColorScheme(
        primary = Color(0xFF4166EB), onPrimary = Color.White,
        primaryContainer = Color(0xFFEAF0FF), onPrimaryContainer = Color(0xFF253F90),
        secondary = Color(0xFF167B6D), secondaryContainer = Color(0xFFE4F4EF),
        background = Color(0xFFF7F8FC), surface = Color(0xFFFFFFFF),
        surfaceVariant = Color(0xFFF0F2F8), outlineVariant = Color(0xFFE2E6F0),
        onSurface = Color(0xFF202638), onSurfaceVariant = Color(0xFF69738B),
    )
    CompositionLocalProvider(LocalDensity provides scaledDensity) {
        MaterialTheme(colorScheme = colors, typography = Typography(), content = content)
    }
}
