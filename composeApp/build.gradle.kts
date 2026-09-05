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
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.material.icons.core)
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
            }
        }
    }
}

// The screenshot test (composeApp/src/desktopTest) writes into build/screenshots unless
// `-Pgains.screenshotDir=<dir>` (relative to the repository root) points it elsewhere.
tasks.withType<Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
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
}

compose.desktop {
    application {
        mainClass = "app.gains.MainKt"
        // `./gradlew :composeApp:run -Pgains.openFile=a.csv,b.csv` opens straight into the import preview.
        project.findProperty("gains.openFile")?.toString()?.split(',')?.filter { it.isNotBlank() }?.let { args += it }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // The Strava sign-in receives its OAuth redirect on a loopback HttpServer (jdk.httpserver).
            modules("jdk.httpserver")
            packageName = "Gains"
            packageVersion = "1.0.0"
        }
    }
}

if (androidEnabled) {
    apply(from = "android.gradle")
}
