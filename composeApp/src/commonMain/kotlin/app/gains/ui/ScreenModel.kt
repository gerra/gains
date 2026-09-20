package app.gains.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.gains.ui.nav.LocalNavEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.mp.KoinPlatform

/** Lightweight state holder: survives recompositions, cancelled when its screen is gone. */
internal abstract class ScreenModel {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    open fun onCleared() = scope.cancel()
}

/**
 * The [ScreenModel] of class [T] for the screen being composed, made by [factory] the first time
 * and again whenever [keys] change.
 *
 * Inside the navigator the model belongs to the screen's back-stack entry and lives as long as
 * that does, so a screen that is covered by another one keeps its state and is back at once when
 * the other is popped, instead of loading from scratch. Outside the navigator (sign-in, onboarding
 * at launch) it lives as long as the composable. One model per class per screen.
 */
@Composable
internal inline fun <reified T : ScreenModel> rememberScreenModel(vararg keys: Any?, noinline factory: () -> T): T {
    val entry = LocalNavEntry.current
    if (entry != null) return remember(entry, *keys) { entry.model(T::class, keys.toList(), factory) }
    val model = remember(*keys) { factory() }
    DisposableEffect(model) { onDispose { model.onCleared() } }
    return model
}

internal inline fun <reified T : Any> inject(): T = KoinPlatform.getKoin().get(T::class)
