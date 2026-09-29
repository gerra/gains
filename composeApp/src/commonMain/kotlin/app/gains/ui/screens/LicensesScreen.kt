package app.gains.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.gains.platform.appVersion
import app.gains.resources.Res
import app.gains.resources.app_version
import app.gains.resources.gains_license
import app.gains.resources.gains_source
import app.gains.resources.libraries
import app.gains.resources.libraries_note
import app.gains.resources.license_texts
import app.gains.resources.name_and_logo
import app.gains.resources.open_source_licenses
import app.gains.resources.read_the_license
import app.gains.resources.source_code
import app.gains.resources.third_party_works
import app.gains.ui.components.Dp16
import app.gains.ui.components.GainsCard
import app.gains.ui.components.ScreenTitle
import app.gains.ui.components.SectionHeader
import app.gains.ui.licenses.Libraries
import app.gains.ui.licenses.Library
import app.gains.ui.licenses.LicenseText
import app.gains.ui.licenses.ThirdPartyWork
import app.gains.ui.licenses.ThirdPartyWorks
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Where Gains' own license and source are. MPL-2.0 section 3.2 asks every build to tell its recipients. */
internal const val MPL_URL = "https://mozilla.org/MPL/2.0/"
internal const val SOURCE_URL = "https://github.com/gerra/gains"

/**
 * Settings → Open-source licenses (docs/launch-plan.md, item 39): the version, Gains' own license and
 * where its source is, the name-and-logo sentence, the works that aren't libraries (NOTICE.md), then
 * every library this build ships with its license, and each license's text once. The same screen on
 * every platform, since neither store shows a license on install.
 */
@Composable
internal fun LicensesScreen() {
    val uriHandler = LocalUriHandler.current
    val palette = GainsColors.palette
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val list by produceState<Libraries?>(null) {
        value = withContext(Dispatchers.Default) { runCatching { Libraries.load() }.getOrNull() }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item {
            ScreenTitle(stringResource(Res.string.open_source_licenses), stringResource(Res.string.app_version, appVersion()))
            GainsCard(Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.gains_license), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(Res.string.gains_source), style = MaterialTheme.typography.bodySmall, color = muted)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { uriHandler.openUri(MPL_URL) }) { Text(stringResource(Res.string.read_the_license), color = palette.volt) }
                    TextButton(onClick = { uriHandler.openUri(SOURCE_URL) }) { Text(stringResource(Res.string.source_code), color = palette.volt) }
                }
                Text(stringResource(Res.string.name_and_logo), style = MaterialTheme.typography.bodySmall, color = muted)
            }
            SectionHeader(stringResource(Res.string.third_party_works))
        }
        items(ThirdPartyWorks.all, key = { "work:" + it.name }) { work -> WorkCard(work) }
        item {
            SectionHeader(stringResource(Res.string.libraries))
            Text(stringResource(Res.string.libraries_note), style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(bottom = 8.dp))
        }
        val loaded = list
        if (loaded != null) {
            val names = loaded.licenses.associate { it.id to it.name }
            items(loaded.libraries, key = { "lib:" + it.id }) { library -> LibraryRow(library, names) }
            item { SectionHeader(stringResource(Res.string.license_texts)) }
            items(loaded.licenses, key = { "license:" + it.id }) { license -> LicenseCard(license) }
        }
    }
}

@Composable
private fun WorkCard(work: ThirdPartyWork) {
    val uriHandler = LocalUriHandler.current
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Text(work.name, style = MaterialTheme.typography.titleSmall)
        Text("${work.author} · ${work.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(work.usedFor, style = MaterialTheme.typography.bodySmall)
        work.notice?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { uriHandler.openUri(work.url) }) { Text(work.url, color = GainsColors.palette.volt, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun LibraryRow(library: Library, licenseNames: Map<String, String>) {
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Text("${library.name} ${library.version}", style = MaterialTheme.typography.titleSmall)
        val details = listOfNotNull(library.author, library.licenseIds.joinToString { licenseNames[it] ?: it }.ifEmpty { null })
        Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun LicenseCard(license: LicenseText) {
    val uriHandler = LocalUriHandler.current
    GainsCard(Modifier.fillMaxWidth().padding(bottom = 6.dp), contentPadding = Dp16.Tight) {
        Text(license.name, style = MaterialTheme.typography.titleSmall)
        license.text?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        license.url?.let { url ->
            TextButton(onClick = { uriHandler.openUri(url) }) { Text(url, color = GainsColors.palette.volt, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
