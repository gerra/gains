import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        if (androidEnabled) {
            androidMain.dependencies {
                implementation(libs.sqldelight.android)
                implementation(libs.kotlinx.coroutines.android)
            }
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native)
        }
        val desktopMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite)
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

if (androidEnabled) {
    apply(from = "android.gradle")
}
