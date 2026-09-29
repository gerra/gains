package app.gains.platform

/**
 * The version this build carries, as the store shows it plus the build number where there is one
 * ("1.12 (240)"), for the Open-source licenses screen: MPL-2.0 asks a build to say where its source
 * is, and the source of every release is tagged with this version and build.
 */
internal expect fun appVersion(): String
