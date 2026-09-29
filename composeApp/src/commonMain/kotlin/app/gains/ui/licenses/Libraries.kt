package app.gains.ui.licenses

import app.gains.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One library compiled into this build, as AboutLibraries found it in its POM. */
internal data class Library(
    val id: String,
    val name: String,
    val version: String,
    /** The organisation, or else the developers, the POM names. */
    val author: String?,
    val website: String?,
    val licenseIds: List<String>,
)

/** A license some [Library] is under. [text] is the full text where AboutLibraries has one. */
internal data class LicenseText(val id: String, val name: String, val url: String?, val text: String?)

/** Every library in this build, by name, and each license they use once. */
internal data class Libraries(val libraries: List<Library>, val licenses: List<LicenseText>) {
    companion object {
        /**
         * The build's list, generated per target by the AboutLibraries plugin into the Compose
         * resources (composeApp/build.gradle.kts), so the desktop, Android and iOS builds each list
         * what they contain. The plugin runs offline, so the build never reaches the network, and
         * offline it names each license without its text: the texts of the licenses the libraries
         * use sit beside it in files/license-texts, one file per license id, as published (GitHub's
         * copies of the SPDX texts). A license with no file there, like Google's Android SDK
         * License, shows its link instead.
         */
        suspend fun load(): Libraries {
            val list = parse(Res.readBytes(PATH).decodeToString())
            return list.copy(licenses = list.licenses.map { license ->
                if (license.text != null) license
                else license.copy(text = runCatching { Res.readBytes("$TEXTS/${license.id}.txt").decodeToString() }.getOrNull())
            })
        }

        const val PATH = "files/libraries.json"
        const val TEXTS = "files/license-texts"

        fun parse(json: String): Libraries {
            val root = Json.parseToJsonElement(json).jsonObject
            val licenses = (root["licenses"] as? JsonObject).orEmpty().map { (id, value) ->
                val o = value.jsonObject
                LicenseText(id, o.string("name") ?: id, o.string("url"), o.string("content"))
            }
            val libraries = root["libraries"]?.jsonArray.orEmpty().map { value ->
                val o = value.jsonObject
                val developers = (o["developers"] as? JsonArray).orEmpty().mapNotNull { it.jsonObject.string("name") }
                Library(
                    id = o.string("uniqueId") ?: "",
                    name = o.string("name") ?: o.string("uniqueId") ?: "",
                    version = o.string("artifactVersion") ?: "",
                    author = (o["organization"] as? JsonObject)?.string("name") ?: developers.joinToString().ifEmpty { null },
                    website = o.string("website"),
                    licenseIds = (o["licenses"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content },
                )
            }
            val used = libraries.flatMap { it.licenseIds }.toSet()
            return Libraries(
                libraries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }),
                licenses.filter { it.id in used }.sortedBy { it.name },
            )
        }

        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
