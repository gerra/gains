package app.gains.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.mp.KoinPlatform

/** Lightweight state holder: survives recompositions, cancelled when its screen leaves composition. */
abstract class ScreenModel {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    open fun onCleared() = scope.cancel()
}

@Composable
fun <T : ScreenModel> rememberScreenModel(vararg keys: Any?, factory: () -> T): T {
    val model = remember(*keys) { factory() }
    DisposableEffect(model) { onDispose { model.onCleared() } }
    return model
}

inline fun <reified T : Any> inject(): T = KoinPlatform.getKoin().get(T::class)
