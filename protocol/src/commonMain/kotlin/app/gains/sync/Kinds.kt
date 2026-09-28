package app.gains.sync

import kotlinx.serialization.Serializable

/**
 * The document kinds that are synced. The server refuses any other, so a new kind reaches both
 * ends through this list; its payload class stays in the app's Documents.kt, since the server
 * never looks inside. The triggers in the app's Sync.sq write the same strings.
 */
object SyncKinds {
    const val SESSION = "session"
    const val SESSION_PHOTO = "session_photo"
    const val EXERCISE = "exercise"
    const val ALIAS = "alias"
    const val OVERRIDE = "override"
    const val BODYWEIGHT = "bodyweight"
    const val PROGRAM = "program"
    const val SETTING = "setting"

    val all = listOf(SESSION, SESSION_PHOTO, EXERCISE, ALIAS, OVERRIDE, BODYWEIGHT, PROGRAM, SETTING)
}

/**
 * A [SyncKinds.SESSION_PHOTO] payload, the one document shape the server writes itself (on a blob
 * upload). The bytes travel on the blob route; the feed carries only enough to know whether to
 * fetch them.
 */
@Serializable
data class PhotoDoc(val sha256: String, val size: Int)
