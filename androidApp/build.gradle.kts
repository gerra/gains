// The Android application: only what must belong to one. AGP 9 no longer takes
// com.android.application in a module that also applies the Kotlin Multiplatform plugin, so the
// app's Kotlin, manifest and resources stay in :composeApp, an Android library, and this module
// wraps it with the application id, the version, the sign-in settings, signing, R8 and lint
// (docs/launch-plan.md, item 38). No Kotlin sources of its own. settings.gradle.kts includes it only
// when gains.android is on, so the Android Gradle Plugin is always on the classpath here.
plugins {
    id("com.android.application")
}

// The version Play shows is the iOS one: MARKETING_VERSION from Config.xcconfig, which the
// release branch bumps, so the two stores never disagree about what 1.9 is. Read here rather
// than passed in, so an Android Studio build carries it too.
fun marketingVersion(): String {
    val config = rootProject.file("iosApp/Configuration/Config.xcconfig")
    return config.readLines()
        .map { it.split("=", limit = 2) }
        .firstOrNull { it.size == 2 && it[0].trim() == "MARKETING_VERSION" }
        ?.get(1)?.trim()
        ?: throw GradleException("MARKETING_VERSION is not set in $config")
}

// The upload key, only when the release workflow supplies it (tools/play.py, docs/play.md).
// Without it a release build stays unsigned, which is what Android Studio's own "Generate
// Signed App Bundle" expects, and the key material never sits in the repository.
val uploadKeystore = findProperty("gains.uploadKeystore")?.toString()

android {
    // Must differ from the library's app.gains, which the manifest's class names and R resolve
    // against. Nothing outside the build sees it: Play and devices know the app by applicationId.
    namespace = "app.gains.android"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // Public on Google Play, and Play never lets it change after the first upload
        // (docs/launch-plan.md, item 8). Kept as "app.gains" on purpose.
        applicationId = "app.gains"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Play wants every upload's versionCode above the last one's: the release workflow passes
        // its run number, the same number TestFlight gets as the build number, and a local build is 1.
        versionCode = (findProperty("gains.versionCode")?.toString() ?: "1").toInt()
        versionName = marketingVersion()
        // The sync server, the Google Web application client, the Apple Services ID and the email
        // form's switch (docs/development.md, "Android"), from the Gradle properties so they change
        // without touching code, like the desktop's system properties. They override the empty
        // defaults in :composeApp's res/values/sign_in_config.xml, which androidAuthConfig() reads.
        // An empty one leaves that provider's button hidden; empty all round leaves the app a guest.
        resValue("string", "gains_server_url", findProperty("gains.serverUrl")?.toString() ?: "")
        resValue("string", "gains_google_web_client_id", findProperty("gains.googleWebClientId")?.toString() ?: "")
        resValue("string", "gains_apple_services_id", findProperty("gains.appleServicesId")?.toString() ?: "")
        resValue("string", "gains_password_sign_in", findProperty("gains.passwordSignIn")?.toString() ?: "")
    }
    buildFeatures {
        // Said explicitly, since AGP 9 changed the defaults: the four resValues above need it.
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    signingConfigs {
        if (uploadKeystore != null) {
            create("upload") {
                storeFile = rootProject.file(uploadKeystore)
                storePassword = findProperty("gains.uploadKeystorePassword")?.toString()
                keyAlias = findProperty("gains.uploadKeyAlias")?.toString()
                keyPassword = findProperty("gains.uploadKeyPassword")?.toString()
            }
        }
    }
    buildTypes {
        // R8 (docs/launch-plan.md, item 30): a smaller bundle that starts quicker. It is hygiene,
        // not a security boundary: the token is in the Keystore, not in the code. The libraries
        // that reflect ship their own consumer rules (kotlinx-serialization, Ktor, OkHttp, Koin,
        // Credential Manager, googleid), and the app's own serializers are all called by name, so
        // proguard-rules.pro starts empty and gains a rule only when a release-only crash asks for
        // one. Names stay obfuscated; tools/play.py sends mapping.txt to Play with the bundle so
        // Play Console's stack traces read.
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (uploadKeystore != null) signingConfig = signingConfigs.getByName("upload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // CI runs lintDebug on every pull request (docs/launch-plan.md, item 25). Errors fail the
    // build and warnings only show in the report, so a new warning never holds a pull request up.
    // checkDependencies: the code is all in :composeApp, so lint follows it there.
    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

dependencies {
    implementation(project(":composeApp"))
}
