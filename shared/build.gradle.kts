import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

if (androidEnabled) {
    apply(plugin = "com.android.library")
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
            // The sync wire format (app.gains.sync) and the client that speaks it. The server
            // module compiles the same classes, so both ends agree on every field.
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

if (androidEnabled) {
    apply(from = "android.gradle")
}
