import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

// Before the kotlin { } block: android.gradle creates the Android target that androidMain belongs to.
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
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets.all {
        languageSettings.optIn("kotlin.time.ExperimentalTime")
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.datetime)
            api(libs.kotlinx.coroutines.core)
            api(libs.koin.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.coroutines)
            // The sync wire format, shared with the server, and the client that speaks it.
            api(project(":protocol"))
            api(libs.kotlinx.serialization.json)
            api(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.sqldelight.android)
                implementation(libs.kotlinx.coroutines.android)
                implementation(libs.ktor.client.okhttp)
            }
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
            implementation(libs.ktor.client.darwin)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite)
                implementation(libs.ktor.client.cio)
            }
        }
        val desktopTest by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite)
                // GoogleOAuthTest answers the token exchange without a network.
                implementation(libs.ktor.client.mock)
            }
        }
    }
}

sqldelight {
    databases {
        create("GainsDatabase") {
            packageName.set("app.gains.db")
            generateAsync.set(false)
        }
    }
}

// LocalizationResourcesTest reads the app's string resources to check the catalogues are covered.
tasks.withType<Test>().configureEach {
    systemProperty("gains.composeResourcesDir", rootProject.file("composeApp/src/commonMain/composeResources").absolutePath)
}

// Java 17 bytecode on the desktop and Android targets alike. Set on the tasks, since the Android
// library plugin's target has no compilerOptions block of its own to say it in.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

if (androidEnabled) {
    // Ktor's OkHttp engine brings okhttp 5.5.0, whose AAR metadata requires compileSdk 37 and
    // fails :androidApp:checkDebugAarMetadata. The version catalog says why 5.4.0.
    dependencies {
        constraints {
            add("androidMainImplementation", "com.squareup.okhttp3:okhttp") {
                version { strictly(libs.versions.okhttp.get()) }
                because("okhttp 5.5.0 needs compileSdk 37; see okhttp in gradle/libs.versions.toml")
            }
        }
    }
}
