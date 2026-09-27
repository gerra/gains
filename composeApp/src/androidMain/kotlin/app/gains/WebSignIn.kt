package app.gains

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import app.gains.auth.SignInCancelledException
import kotlinx.coroutines.CompletableDeferred

/**
 * The hand-off between a sign-in that left for the browser and the browser coming back
 * (docs/sync.md, "Sign in with Apple without Apple's sheet"). [AndroidIdentityProvider] opens the
 * server's start page in a Custom Tab and waits on [expect]; the tab ends on the App Link
 * `https://gains.gerra.sh/auth/done`, which Android hands to [SignInCallbackActivity], and that
 * activity [deliver]s the URL. There is no callback when the person backs out of the tab, so
 * `MainActivity` coming back to the front with a sign-in still waiting ([onAppResumed]) is the
 * cancel: the browser closes before the app resumes, and on the way back with a code the callback
 * arrives first, from the activity that then brings `MainActivity` forward.
 *
 * One process, one sign-in at a time: a second tap replaces the first wait, which ends as a cancel.
 */
internal object WebSignIn {
    private var pending: CompletableDeferred<String>? = null

    /** Starts waiting for the browser; the result is the callback URL it came back with. */
    fun expect(): CompletableDeferred<String> {
        pending?.completeExceptionally(SignInCancelledException())
        return CompletableDeferred<String>().also { pending = it }
    }

    /** The browser came back with [url]. False when no sign-in was waiting for it (a stale link). */
    fun deliver(url: String): Boolean {
        val waiting = pending ?: return false
        pending = null
        return waiting.complete(url)
    }

    /** The app is in front again; a sign-in still waiting was backed out of. */
    fun onAppResumed() {
        val waiting = pending ?: return
        pending = null
        waiting.completeExceptionally(SignInCancelledException())
    }
}

/**
 * Receives the App Link the sign-in ends on (`AndroidManifest.xml`), hands its URL to [WebSignIn]
 * and brings `MainActivity` back to the front, which finishes the Custom Tab that sat above it.
 * It has no UI of its own (a translucent theme, no history), so nothing flashes on the way.
 *
 * A separate activity, rather than the link on `MainActivity`, because the browser starts the link's
 * activity on top of itself: with `MainActivity` as the target Android would make a second copy
 * above the tab instead of returning to the one already running. Starting `MainActivity` from here
 * with `CLEAR_TOP | SINGLE_TOP` reaches the running copy through `onNewIntent` and drops everything
 * above it; `NEW_TASK` finds the app's task when the browser put this activity in one of its own.
 */
class SignInCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { WebSignIn.deliver(it.toString()) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
