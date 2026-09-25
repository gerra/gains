import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

if (androidEnabled) {
    apply(plugin = "com.android.application")
}

kotlin {
    if (androidEnabled) {
        androidTarget {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_17)
            }
        }
    }

    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        // Only the three Swift entry points in iosMain (MainViewController, prepareLiveSessionNotices,
        // handleIncomingFile) are public; everything else in this module is `internal` so the
        // Objective-C header stays small and does not pull in Compose or shared types, whose nested
        // classes otherwise show up in Xcode as "imported declaration could not be mapped" warnings.
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
            // SQLDelight's native driver calls the platform SQLite C API. Because this
            // is the final Kotlin framework consumed by Xcode, keep that native linker
            // dependency on the exported framework as well as on the Xcode app target.
            linkerOpts("-lsqlite3")
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
        languageSettings.optIn("kotlinx.coroutines.ExperimentalCoroutinesApi")
        // Res.allStringResources (the catalogue names) and the resource environment for screen models.
        languageSettings.optIn("org.jetbrains.compose.resources.ExperimentalResourceApi")
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
            implementation(compose.components.resources)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.android.activity.compose)
                implementation(libs.koin.android)
            }
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(compose.desktop.uiTestJUnit4)
                // DesktopIdentityProviderTest answers Google's token endpoint without a network.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

// Every string the UI shows lives in src/commonMain/composeResources/values/strings.xml, with a
// values-<lang>/strings.xml per translation; the plugin generates `Res` from them. The Res class is
// kept internal, like the rest of the UI, so it stays out of the iOS framework's header.
compose.resources {
    packageOfResClass = "app.gains.resources"
    publicResClass = false
}

// The screenshot test (composeApp/src/desktopTest) writes into build/screenshots unless
// `-Pgains.screenshotDir=<dir>` (relative to the repository root) points it elsewhere.
tasks.withType<Test>().configureEach {
    // Recording the motion clips (-Pgains.animationDir) saves several hundred frames on top of the screenshots.
    timeout.set(Duration.ofMinutes(if (project.hasProperty("gains.animationDir")) 25 else 10))
    // The UI tests look for English text and the screenshots are the README's, whatever the runner's locale.
    jvmArgs("-Duser.language=en", "-Duser.country=US")
    testLogging {
        showStandardStreams = true
        events("passed", "failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    systemProperty(
        "gains.screenshotDir",
        project.findProperty("gains.screenshotDir")?.toString()?.let { rootProject.file(it).absolutePath }
            ?: layout.buildDirectory.dir("screenshots").get().asFile.absolutePath,
    )
    systemProperty("gains.sampleCsv", rootProject.file("samples/liftoff-export.csv").absolutePath)
    // `-Pgains.animationDir=<dir>` makes the screenshot test record clips of the app's motion there too.
    project.findProperty("gains.animationDir")?.let { systemProperty("gains.animationDir", rootProject.file(it.toString()).absolutePath) }
}

compose.desktop {
    application {
        mainClass = "app.gains.MainKt"
        // `./gradlew :composeApp:run -Pgains.openFile=a.csv,b.csv` opens straight into the import preview.
        project.findProperty("gains.openFile")?.toString()?.split(',')?.filter { it.isNotBlank() }?.let { args += it }
        // The sync server and the Google Desktop app client (docs/development.md, "Desktop"), passed
        // to the app as system properties, so `run` and the packaged installers both carry them.
        // The secret is not in gradle.properties: pass it with -P or keep it in ~/.gradle/gradle.properties.
        for (name in listOf("gains.serverUrl", "gains.googleDesktopClientId", "gains.googleDesktopClientSecret")) {
            project.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }?.let { jvmArgs += "-D$name=$it" }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gains"
            packageVersion = "1.0.0"
        }
    }
}

if (androidEnabled) {
    apply(from = "android.gradle")
}
