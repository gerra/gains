package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.auth.AccountRepository
import app.gains.auth.AuthNotConfiguredException
import app.gains.auth.EmailSignInException
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.components.PrimaryButton
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * The email and password form's state (docs/sync.md, "Signing in"), shared by the welcome screen
 * and Settings the way [SignInAttempt] is for the sheets. Three modes on the same two fields:
 * signing in, creating an account (which ends with a mail to answer, not with an account) and
 * asking for a reset link (which ends the same way whether or not the address is known). What
 * the server said is kept as an [Outcome] the screen words; the plainly wrong (a blank address,
 * a password under the server's minimum) is caught here so a tap costs no round trip.
 * [onSignedIn] runs after a sign-in went through, for Settings to ask for a sync.
 */
internal class EmailSignIn(
    private val scope: CoroutineScope,
    private val accounts: AccountRepository,
    private val onSignedIn: () -> Unit = {},
) {
    enum class Mode { SIGN_IN, SIGN_UP, RESET }

    sealed interface Outcome {
        /** A sign-up went through: the confirmation link is in the person's inbox. */
        data object CheckInbox : Outcome
        /** A reset was asked for; the server says the same whether or not the address is known. */
        data object ResetSent : Outcome
        /** The server, or the check here, refused the address or password for the reason given. */
        data class Refused(val reason: EmailSignInException.Reason) : Outcome
        /** This build, or the server, has no email sign-in. */
        data object NotConfigured : Outcome
        /** The server could not be reached, or answered with something unexpected. */
        data object Failed : Outcome
    }

    var mode by mutableStateOf(Mode.SIGN_IN)
        private set
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    /** A call is out; the button waits for it. */
    var running by mutableStateOf(false)
        private set
    var outcome by mutableStateOf<Outcome?>(null)
        private set

    /** Changes what the button does; the fields keep what was typed, the last outcome goes. */
    fun switchTo(mode: Mode) {
        this.mode = mode
        outcome = null
    }

    fun submit(): Job {
        if (running) return Job().apply { complete() }
        val address = email.trim()
        val refused = when {
            address.isBlank() || '@' !in address -> EmailSignInException.Reason.INVALID
            mode != Mode.RESET && password.length < MIN_PASSWORD -> EmailSignInException.Reason.INVALID
            else -> null
        }
        if (refused != null) {
            outcome = Outcome.Refused(refused)
            return Job().apply { complete() }
        }
        running = true
        outcome = null
        return scope.launch {
            try {
                when (mode) {
                    Mode.SIGN_IN -> {
                        accounts.signInWithEmail(address, password)
                        password = ""
                        onSignedIn()
                    }
                    Mode.SIGN_UP -> {
                        accounts.signUpWithEmail(address, password)
                        password = ""
                        outcome = Outcome.CheckInbox
                        mode = Mode.SIGN_IN
                    }
                    Mode.RESET -> {
                        accounts.requestPasswordReset(address)
                        outcome = Outcome.ResetSent
                        mode = Mode.SIGN_IN
                    }
                }
            } catch (e: EmailSignInException) {
                outcome = Outcome.Refused(e.reason)
            } catch (e: AuthNotConfiguredException) {
                outcome = Outcome.NotConfigured
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                outcome = Outcome.Failed
            } finally {
                running = false
            }
        }
    }

    companion object {
        /** The server's rule (`PasswordSignIn.MIN_PASSWORD`), checked here first so the message is instant. */
        const val MIN_PASSWORD = 8
    }
}

/** The two fields, the button for the mode, the links to the other modes, and the last outcome under them. */
@Composable
internal fun EmailSignInForm(form: EmailSignIn, modifier: Modifier = Modifier) {
    val palette = GainsColors.palette
    val colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.volt,
        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    Column(modifier) {
        OutlinedTextField(
            value = form.email, onValueChange = { form.email = it },
            label = { Text(stringResource(Res.string.email_field)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = if (form.mode == EmailSignIn.Mode.RESET) ImeAction.Done else ImeAction.Next),
            shape = MaterialTheme.shapes.medium, colors = colors, enabled = !form.running,
            modifier = Modifier.fillMaxWidth(),
        )
        if (form.mode != EmailSignIn.Mode.RESET) {
            Spacer(Modifier.height(8.dp))
            // The length rule is worth a line when choosing a password, not when typing a known one.
            val hint: (@Composable () -> Unit)? = if (form.mode == EmailSignIn.Mode.SIGN_UP) ({ Text(stringResource(Res.string.email_password_hint)) }) else null
            OutlinedTextField(
                value = form.password, onValueChange = { form.password = it },
                label = { Text(stringResource(Res.string.password_field)) }, singleLine = true,
                supportingText = hint,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                shape = MaterialTheme.shapes.medium, colors = colors, enabled = !form.running,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(10.dp))
        val label = when (form.mode) {
            EmailSignIn.Mode.SIGN_IN -> stringResource(Res.string.email_sign_in)
            EmailSignIn.Mode.SIGN_UP -> stringResource(Res.string.email_create_account)
            EmailSignIn.Mode.RESET -> stringResource(Res.string.email_send_reset)
        }
        PrimaryButton(label, onClick = { form.submit() }, modifier = Modifier.fillMaxWidth(), enabled = !form.running)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            when (form.mode) {
                EmailSignIn.Mode.SIGN_IN -> {
                    TextButton(onClick = { form.switchTo(EmailSignIn.Mode.SIGN_UP) }) { Text(stringResource(Res.string.email_no_account), color = palette.volt) }
                    TextButton(onClick = { form.switchTo(EmailSignIn.Mode.RESET) }) { Text(stringResource(Res.string.email_forgot), color = palette.volt) }
                }
                EmailSignIn.Mode.SIGN_UP, EmailSignIn.Mode.RESET -> {
                    TextButton(onClick = { form.switchTo(EmailSignIn.Mode.SIGN_IN) }) { Text(stringResource(Res.string.email_have_account), color = palette.volt) }
                }
            }
        }
        val outcome = form.outcome
        if (outcome != null) {
            val error = outcome is EmailSignIn.Outcome.Refused || outcome is EmailSignIn.Outcome.Failed || outcome is EmailSignIn.Outcome.NotConfigured
            Text(
                emailOutcomeText(outcome),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun emailOutcomeText(outcome: EmailSignIn.Outcome): String = when (outcome) {
    EmailSignIn.Outcome.CheckInbox -> stringResource(Res.string.email_check_inbox)
    EmailSignIn.Outcome.ResetSent -> stringResource(Res.string.email_reset_sent)
    EmailSignIn.Outcome.NotConfigured -> stringResource(Res.string.sign_in_not_configured, stringResource(Res.string.account_email))
    EmailSignIn.Outcome.Failed -> stringResource(Res.string.sign_in_failed)
    is EmailSignIn.Outcome.Refused -> when (outcome.reason) {
        EmailSignInException.Reason.WRONG_CREDENTIALS -> stringResource(Res.string.email_wrong)
        EmailSignInException.Reason.NOT_CONFIRMED -> stringResource(Res.string.email_not_confirmed)
        EmailSignInException.Reason.INVALID -> stringResource(Res.string.email_invalid)
        EmailSignInException.Reason.TOO_MANY -> stringResource(Res.string.email_too_many)
    }
}
