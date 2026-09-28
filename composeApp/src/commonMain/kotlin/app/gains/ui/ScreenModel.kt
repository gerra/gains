package app.gains.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import app.gains.ErrorReporter
import app.gains.ui.i18n.LocalAppLanguage
import app.gains.ui.nav.LocalNavEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/**
 * Lightweight state holder: survives recompositions, cancelled when its screen is gone.
 *
 * Its actions go through [launchAction]. An exception that still escapes a coroutine in [scope]
 * lands in the scope's handler ([reportingHandler]), which hands it to [reporter] and keeps the
 * app running: without one, an uncaught exception ends an iOS app and crashes an Android one.
 */
internal abstract class ScreenModel(protected val reporter: ErrorReporter = inject()) {
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + reportingHandler(reporter))
    open fun onCleared() = scope.cancel()
}

/**
 * Launches an action in this scope, the one way the models do (launch plan item 34). [block]
 * catches the failures it expects and has words for (a closed sign-in sheet, a CSV in an unknown
 * format) itself, as before. Anything else is unexpected: [onFailure] gets it so the screen can
 * show the failure state it has, and it is rethrown to the scope's handler, which reports it once
 * (a [ScreenModel]'s scope has [reportingHandler]).
 *
 * Cancellation is not a failure: the model is being cleared, so nothing is reported and nothing
 * written, even when the cancelled call surfaces as some other exception. An [Error] is not caught
 * at all, and stays a crash.
 */
internal fun CoroutineScope.launchAction(onFailure: (Exception) -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Job = launch {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ensureActive()
        onFailure(e)
        throw e
    }
}

/**
 * The last line of a [ScreenModel]'s scope: reports an [Exception] that escaped a coroutine and
 * swallows it, so a failed write costs that write and not the app. An [Error] is rethrown, which
 * sends it on to the platform as if there were no handler: an out-of-memory or a broken invariant
 * should still crash.
 */
internal fun reportingHandler(reporter: ErrorReporter) = CoroutineExceptionHandler { _, e ->
    if (e is Exception) reporter.report(e) else throw e
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
    // A model is worded in the language of the composition that made it (it holds Texts), and its
    // screen outlives that composition on the back stack, so the language is one of its keys too.
    val language = LocalAppLanguage.current
    if (entry != null) return remember(entry, language, *keys) { entry.model(T::class, keys.toList() + language, factory) }
    val model = remember(language, *keys) { factory() }
    DisposableEffect(model) { onDispose { model.onCleared() } }
    return model
}

internal inline fun <reified T : Any> inject(): T = KoinPlatform.getKoin().get(T::class)
