# R8 rules of the app's own for the release build (androidApp/build.gradle.kts,
# docs/launch-plan.md, item 30).
#
# Empty on purpose. What reflects brings its own consumer rules: kotlinx-serialization (the
# @Serializable classes in Protocol.kt, Documents.kt and GoogleOAuth.kt, whose serializers the
# app also calls by name), Ktor and its OkHttp engine (created explicitly, so no ServiceLoader),
# Koin (the constructor DSL, no reflection), Credential Manager and googleid. SQLDelight
# generates plain code and needs none.
#
# Add a rule only when a release build asks for one, with a comment saying which crash it fixes:
# a ClassNotFoundException or a serializer "not found" on the device is a missing keep rule, and
# an R8 "Missing class" error in bundleRelease writes the -dontwarn lines it wants to
# androidApp/build/outputs/mapping/release/missing_rules.txt.
