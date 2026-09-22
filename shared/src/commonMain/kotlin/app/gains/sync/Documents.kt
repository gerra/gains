package app.gains.sync

import app.gains.data.ProgramCodec
import app.gains.data.SettingsRepository
import app.gains.data.ExerciseRepository
import app.gains.data.ProgramRepository
import app.gains.domain.Exercise
import app.gains.domain.ExerciseEntry
import app.gains.domain.ExerciseSlot
import app.gains.domain.Experience
import app.gains.domain.Modality
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramDayRef
import app.gains.domain.RepTarget
import app.gains.domain.Session
import app.gains.domain.SetEntry
import app.gains.domain.SetType
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/*
 * What goes inside a SyncDocument's payload, one class per kind. A session travels whole with its
 * entries and sets, a program with its days and slots: the server never looks inside, and the
 * client replaces the whole thing on apply. The compact text forms ProgramCodec already uses for
 * SQLite (reps, progression, goals, equipment, muscles) are reused as they are.
 */

/** The document kinds and the setting keys that are synced. The trigger in Sync.sq names the same keys. */
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

    /** The person's preferences, not the device's: theme, language and the reminder stay where they are. */
    val settingKeys = setOf(
        SettingsRepository.KEY_UNIT,
        SettingsRepository.KEY_BAR_WEIGHT,
        SettingsRepository.KEY_AUTO_WARMUPS,
        ProgramRepository.KEY_GOAL_PROFILE,
        ProgramRepository.KEY_ACTIVE_PROGRAM,
        ProgramRepository.KEY_ONBOARDING,
    )
}

@Serializable
data class SessionDoc(
    val timestamp: String,
    val durationMinutes: Int? = null,
    val source: String = Session.IMPORTED,
    val programId: String? = null,
    val programDayId: String? = null,
    val caption: String? = null,
    val exercises: List<EntryDoc> = emptyList(),
) {
    fun toSession(id: String): Session = Session(
        id = id,
        timestamp = LocalDateTime.parse(timestamp),
        durationMinutes = durationMinutes,
        exercises = exercises.map { it.toEntry() },
        source = source,
        program = if (programId.isNullOrBlank() || programDayId.isNullOrBlank()) null else ProgramDayRef(programId, programDayId),
        caption = caption,
    )

    companion object {
        fun of(s: Session) = SessionDoc(
            timestamp = s.timestamp.toString(),
            durationMinutes = s.durationMinutes,
            source = s.source,
            programId = s.program?.programId,
            programDayId = s.program?.dayId,
            caption = s.caption,
            exercises = s.exercises.map { EntryDoc.of(it) },
        )
    }
}

@Serializable
data class EntryDoc(val exerciseId: String, val note: String? = null, val sets: List<SetDoc> = emptyList()) {
    fun toEntry() = ExerciseEntry(exerciseId, sets.map { it.toSet() }, note)

    companion object {
        fun of(e: ExerciseEntry) = EntryDoc(e.exerciseId, e.note, e.sets.map { SetDoc.of(it) })
    }
}

@Serializable
data class SetDoc(
    val order: Int,
    val type: String,
    val weightKg: Double? = null,
    val reps: Int? = null,
    val seconds: Int? = null,
    val distanceKm: Double? = null,
    val rpe: Double? = null,
    val isWarmup: Boolean = false,
) {
    fun toSet() = SetEntry(order, SetType.entries.firstOrNull { it.name == type } ?: SetType.WEIGHTED, weightKg, reps, seconds, distanceKm, rpe, isWarmup)

    companion object {
        fun of(s: SetEntry) = SetDoc(s.order, s.type.name, s.weightKg, s.reps, s.seconds, s.distanceKm, s.rpe, s.isWarmup)
    }
}

/** The bytes travel on the blob route; the feed carries only enough to know whether to fetch them. */
@Serializable
data class PhotoDoc(val sha256: String, val size: Int)

@Serializable
data class ExerciseDoc(
    val name: String,
    val canonicalName: String,
    val modality: String,
    val isDumbbell: Boolean = false,
    /** "CHEST:1.0,TRICEPS:0.5", as stored. */
    val muscles: String = "",
    /** "BARBELL,DUMBBELL", as stored. */
    val equipment: String = "",
) {
    fun toExercise(id: String) = Exercise(
        id = id,
        name = name,
        canonicalName = canonicalName,
        muscleGroups = ExerciseRepository.decodeMuscles(muscles),
        modality = Modality.entries.firstOrNull { it.name == modality } ?: Modality.WEIGHTED,
        isDumbbell = isDumbbell,
        isBuiltIn = false,
        equipment = ProgramCodec.decodeEquipment(equipment),
    )

    companion object {
        fun of(e: Exercise) = ExerciseDoc(
            e.name, e.canonicalName, e.modality.name, e.isDumbbell,
            ExerciseRepository.encodeMuscles(e.muscleGroups), ProgramCodec.encodeEquipment(e.equipment),
        )
    }
}

@Serializable
data class AliasDoc(val exerciseId: String)

@Serializable
data class OverrideDoc(val workingSetRatio: Double? = null)

@Serializable
data class BodyweightDoc(val kg: Double)

@Serializable
data class SettingDoc(val value: String)

@Serializable
data class ProgramDoc(
    val name: String,
    val description: String = "",
    /** "BUILD_MUSCLE,GET_STRONGER", as stored. */
    val goals: String = "",
    val level: String = Experience.BEGINNER.name,
    val daysPerWeek: Int = 3,
    val createdAt: String,
    val days: List<DayDoc> = emptyList(),
) {
    fun toProgram(id: String) = Program(
        id = id,
        name = name,
        description = description,
        goals = ProgramCodec.decodeGoals(goals),
        level = Experience.entries.firstOrNull { it.name == level } ?: Experience.BEGINNER,
        daysPerWeek = daysPerWeek,
        days = days.map { it.toDay() },
        isBuiltIn = false,
    )

    companion object {
        fun of(p: Program, createdAt: String) = ProgramDoc(
            p.name, p.description, ProgramCodec.encodeGoals(p.goals), p.level.name, p.daysPerWeek, createdAt,
            p.days.map { DayDoc.of(it) },
        )
    }
}

@Serializable
data class DayDoc(val id: String, val name: String, val slots: List<SlotDoc> = emptyList()) {
    fun toDay() = ProgramDay(id, name, slots.map { it.toSlot() })

    companion object {
        fun of(d: ProgramDay) = DayDoc(d.id, d.name, d.slots.map { SlotDoc.of(it) })
    }
}

@Serializable
data class SlotDoc(
    val exerciseId: String,
    val sets: Int,
    val reps: String,
    val lastSetAmrap: Boolean = false,
    val progression: String = "",
    val note: String? = null,
) {
    fun toSlot() = ExerciseSlot(
        exerciseId, sets, ProgramCodec.decodeReps(reps) ?: RepTarget.Fixed(5), lastSetAmrap, ProgramCodec.decodeRule(progression), note,
    )

    companion object {
        fun of(s: ExerciseSlot) = SlotDoc(s.exerciseId, s.sets, ProgramCodec.encodeReps(s.reps), s.lastSetAmrap, ProgramCodec.encodeRule(s.progression), s.note)
    }
}
