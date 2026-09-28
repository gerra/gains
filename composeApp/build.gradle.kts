import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

// Before the kotlin { } block: android.gradle creates the Android target that androidMain belongs
// to. This module is an Android library; the application is :androidApp.
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    apply(from = "android.gradle")
}

kotlin {
    jvm("desktop")

    listOf(
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
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.coroutines.core)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.compose.ui.tooling.preview)
                implementation(libs.android.activity.compose)
                implementation(libs.koin.android)
                // Sign in with Google through Credential Manager (AndroidIdentityProvider). The
                // play-services artifact is the provider that actually shows the account chooser.
                implementation(libs.androidx.credentials)
                implementation(libs.androidx.credentials.play.services)
                implementation(libs.googleid)
                // Sign in with Apple through the server's web flow in a Custom Tab (AndroidIdentityProvider).
                implementation(libs.androidx.browser)
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
                implementation(libs.compose.ui.test.junit4)
                // LiveSessionNoticesTest runs the rest countdown on virtual time.
                implementation(libs.kotlinx.coroutines.test)
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
    val minutes = if (project.hasProperty("gains.animationDir")) 25L else 10L
    timeout.set(Duration.ofMinutes(minutes))
    // runDesktopComposeUiTest wraps the test in kotlinx-coroutines-test's runTest, whose default
    // limit is a minute, and ScreenshotTest walks the whole app for several. The task timeout above
    // stays the one limit, as it was on Compose Multiplatform 1.7.
    systemProperty("kotlinx.coroutines.test.default_timeout", "${minutes}m")
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
        // The sync server, the Google Desktop app client and the Apple Services ID (docs/development.md,
        // "Desktop"), passed to the app as system properties, so `run` and the packaged installers both
        // carry them. The secret is not in gradle.properties: pass it with -P or keep it in ~/.gradle/gradle.properties.
        for (name in listOf("gains.serverUrl", "gains.googleDesktopClientId", "gains.googleDesktopClientSecret", "gains.appleServicesId", "gains.passwordSignIn")) {
            project.findProperty(name)?.toString()?.takeIf { it.isNotBlank() }?.let { jvmArgs += "-D$name=$it" }
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gains"
            packageVersion = "1.0.0"
        }
    }
}

// Java 17 bytecode on the desktop and Android targets alike. Set on the tasks, since the Android
// library plugin's target has no compilerOptions block of its own to say it in.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
