package se.elpris.app

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import se.elpris.app.ui.MainScreen

class MainActivity : ComponentActivity() {

    private val forecast = mutableStateOf<Forecast?>(null)
    private val status = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = ForecastRepository(applicationContext)
        forecast.value = repo.cached()
        RefreshWorker.schedule(applicationContext)
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
            .launch(Manifest.permission.POST_NOTIFICATIONS)

        setContent {
            val settings by Settings.flow(applicationContext).collectAsState(initial = UserSettings())
            val scope = rememberCoroutineScope()
            MaterialTheme(colorScheme = darkColorScheme()) {
                MainScreen(
                    forecast = forecast.value,
                    settings = settings,
                    status = status.value,
                    onRefresh = { scope.launch { refresh(repo) } },
                    onSettings = { s ->
                        scope.launch {
                            Settings.update(applicationContext, s)
                            PriceWidget().updateAll(applicationContext)
                        }
                    },
                )
            }
        }
        lifecycleScope.launch { refresh(repo) }
    }

    private suspend fun refresh(repo: ForecastRepository) {
        status.value = "Hämtar…"
        repo.refresh()
            .onSuccess {
                forecast.value = it
                status.value = null
                PriceWidget().updateAll(applicationContext)
            }
            .onFailure { status.value = "Kunde inte hämta: ${it.message}" }
    }
}
