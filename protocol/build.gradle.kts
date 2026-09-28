import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The sync wire format (docs/sync.md) and nothing else: the request and response classes, the
// JSON settings, the document kinds and the few constants both ends must agree on. :shared and
// :server both depend on it, so the server no longer carries the app's database, DI container
// and HTTP client, and a client-only change in :shared doesn't rebuild or redeploy the server
// (launch plan, item 37). Only kotlinx-serialization, so it stays cheap to depend on.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
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

    // The desktop app and the server both consume this one.
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}

if (androidEnabled) {
    apply(from = "android.gradle")
}
