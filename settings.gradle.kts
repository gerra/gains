rootProject.name = "Gains"

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":protocol")
include(":shared")
include(":composeApp")
include(":server")
// The Android application (docs/launch-plan.md, item 38), only when the Android Gradle Plugin is
// on: -Pgains.android=false (the test CI job, Xcode's build phase, a Mac without an Android SDK)
// never configures it.
if (providers.gradleProperty("gains.android").getOrElse("true").toBoolean()) {
    include(":androidApp")
}
