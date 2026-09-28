import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

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

// Before the kotlin { } block: android.gradle creates the Android target.
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    apply(from = "android.gradle")
}

kotlin {
    // The desktop app and the server both consume this one.
    jvm()

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}

// Java 17 bytecode on the JVM and Android targets alike. Set on the tasks, since the Android
// library plugin's target has no compilerOptions block of its own to say it in.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
