import com.mikepenz.aboutlibraries.plugin.AboutLibrariesTask
import com.mikepenz.aboutlibraries.plugin.StrictMode
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import java.time.Duration

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.aboutlibraries)
}

val androidEnabled = rootProject.extra["androidEnabled"] as Boolean

// Before the kotlin { } block: android.gradle creates the Android target that androidMain belongs
// to. This module is an Android library; the application is :androidApp. The lint plugin gives
// the library a lint model, without which :androidApp:lintDebug (checkDependencies) skips this
// module, where all the app's code is.
if (androidEnabled) {
    apply(plugin = "com.android.kotlin.multiplatform.library")
    apply(plugin = "com.android.lint")
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
        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(libs.kotlinx.coroutines.swing)
            }
        }
        getByName("desktopTest") {
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

// The Open-source licenses screen (docs/launch-plan.md, item 39) lists the libraries a build ships,
// each with its license. AboutLibraries reads them from the POMs of each target's own dependencies,
// so the desktop, Android and iOS builds each carry the list of what is in them, as
// files/libraries.json in that target's Compose resources, rebuilt whenever the dependencies change.
// Strict mode fails the build on a license that isn't allowed here, so a new dependency under
// another license stops the build instead of reaching a store unnoticed. The works that aren't
// Maven dependencies (the body drawing) are in ThirdPartyWorks.kt, from NOTICE.md.
val licenseLists = buildMap {
    put("desktop", "desktopMain")
    put("iosArm64", "iosArm64Main")
    put("iosSimulatorArm64", "iosSimulatorArm64Main")
    if (androidEnabled) put("android", "androidMain")
}

aboutLibraries {
    // Everything comes from the POMs Gradle has already resolved; nothing is fetched.
    offlineMode = true
    // Kotlin/Native builds against klibs, not a classpath, and the plugin only looks at
    // *Classpath configurations unless it collects them all (see the iOS tasks below).
    collect { all = true }
    export { excludeFields.addAll("description", "scm", "funding") }
    exports {
        licenseLists.forEach { (variant, sourceSet) ->
            create(variant) { outputFile = layout.buildDirectory.file("generated/licenses/$sourceSet/files/libraries.json") }
        }
    }
    license {
        strictMode = StrictMode.FAIL
        // ASDKL is the Android SDK license of Google Play services and the googleid library, which
        // Sign in with Google on Android needs (item 9). Nothing else carries it. A license added
        // here needs its text in composeResources/files/license-texts too: offline, the plugin
        // names licenses without their text, and the screen reads it from there (Libraries.kt).
        allowedLicenses.addAll("Apache-2.0", "MIT", "ASDKL")
    }
}

// An iOS target's task picks its target's configurations (iosArm64CompileKlibraries and the rest)
// by name, then keeps only those called <target>CompileClasspath or <target>RuntimeClasspath, which a
// native target has none of, and writes an empty list. The plugin names the target after choosing,
// so clearing the name then keeps everything chosen. Its output file came from that name too.
tasks.withType<AboutLibrariesTask>().matching { it.name.startsWith("exportLibraryDefinitionsIos") }.configureEach {
    val sourceSet = licenseLists.getValue(variant.get())
    variant.set(null as String?)
    configureOutputFile(layout.buildDirectory.file("generated/licenses/$sourceSet/files/libraries.json"))
}
// The plugin makes no task for the Android target: its Android hook makes one per AGP variant
// instead (exportLibraryDefinitionsAndroidMain), which looks for configurations named after the
// variant and finds none, since the Kotlin target's are androidCompileClasspath and
// androidRuntimeClasspath. This one is what the plugin registers for any other Kotlin target.
if (androidEnabled) {
    tasks.register<AboutLibrariesTask>("exportLibraryDefinitionsAndroid") {
        variant.set("android")
        configure()
        configureOutputFile(layout.buildDirectory.file("generated/licenses/androidMain/files/libraries.json"))
    }
}

// Each list becomes its source set's Compose resources directory, reached through a task of ours
// that depends on the export by name, so the resource tasks run the export first whenever the plugin
// happens to register it.
compose.resources {
    licenseLists.forEach { (variant, sourceSet) ->
        val list = tasks.register("licenseList${sourceSet.replaceFirstChar { it.uppercase() }}") {
            dependsOn("exportLibraryDefinitions${variant.replaceFirstChar { it.uppercase() }}")
        }
        customDirectory(sourceSet, list.map { layout.buildDirectory.dir("generated/licenses/$sourceSet").get() })
    }
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
        // The version the Open-source licenses screen shows: the iOS and Play one, MARKETING_VERSION
        // from Config.xcconfig, read the way :androidApp reads it.
        rootProject.file("iosApp/Configuration/Config.xcconfig").readLines()
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.size == 2 && it[0].trim() == "MARKETING_VERSION" }
            ?.let { jvmArgs += "-Dgains.version=${it[1].trim()}" }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Gains"
            packageVersion = "1.0.0"
            // MPL-2.0 (item 39): the MSI shows this file as its license agreement, and the DMG and
            // the .deb carry it. Third-party notices are in the app, on the Open-source licenses screen.
            licenseFile.set(rootProject.file("LICENSE"))
            copyright = "Copyright © 2026 German Berezhko"
            vendor = "German Berezhko"
        }
    }
}

// Java 17 bytecode on the desktop and Android targets alike. Set on the tasks, since the Android
// library plugin's target has no compilerOptions block of its own to say it in.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}
