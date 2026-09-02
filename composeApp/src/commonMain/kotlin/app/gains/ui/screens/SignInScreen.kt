package app.gains.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.auth.AccountRepository
import app.gains.auth.AuthConfig
import app.gains.auth.AuthNotConfiguredException
import app.gains.ui.ScreenModel
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.launch

class SignInModel(
    private val accounts: AccountRepository = inject(),
    val config: AuthConfig = inject(),
) : ScreenModel() {
    var error by mutableStateOf<String?>(null)
        private set

    fun continueAsGuest() = scope.launch { accounts.continueAsGuest() }

    fun signInWithGoogle() = scope.launch {
        try { accounts.signInWithGoogle() } catch (e: AuthNotConfiguredException) { error = e.message }
    }

    fun signInWithApple() = scope.launch {
        try { accounts.signInWithApple() } catch (e: AuthNotConfiguredException) { error = e.message }
    }
}

/** First-launch gate. Google and Apple sign-in stay disabled until their credentials are configured. */
@Composable
fun SignInScreen() {
    val model = rememberScreenModel { SignInModel() }
    val palette = GainsColors.palette
    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(84.dp).clip(CircleShape).background(palette.volt), contentAlignment = Alignment.Center) {
            Text("↑", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(24.dp))
        Text("Gains", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Import your Liftoff exports and see what's actually moving, what's stalled and what's gone backwards.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))

        ProviderButton("Continue with Google", enabled = model.config.googleEnabled, onClick = { model.signInWithGoogle() })
        Spacer(Modifier.height(10.dp))
        ProviderButton("Continue with Apple", enabled = model.config.appleEnabled, onClick = { model.signInWithApple() })
        if (!model.config.googleEnabled || !model.config.appleEnabled) {
            Spacer(Modifier.height(10.dp))
            Pill("Sign-in and sync coming soon", palette.muted)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton("Continue as guest", onClick = { model.continueAsGuest() }, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text(
            "As a guest everything is saved on this device only. You can sign in later to back it up and sync.",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model.error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ProviderButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        ),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    }
}
