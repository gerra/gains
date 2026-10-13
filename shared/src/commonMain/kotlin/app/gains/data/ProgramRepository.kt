package app.gains.data

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.gains.catalogue.ProgramCatalogue
import app.gains.db.GainsDatabase
import app.gains.domain.Experience
import app.gains.domain.ExerciseSlot
import app.gains.domain.GoalProfile
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.domain.ProgramState
import app.gains.domain.RepTarget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Built-in programs come from [ProgramCatalogue]; the user's own live in the program tables.
 * The goal profile, the active program, when each program was last started over and the onboarding
 * flag are plain settings.
 */
class ProgramRepository(
    private val db: GainsDatabase,
    private val settings: SettingsRepository,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val q get() = db.programQueries

    fun observeCustomPrograms(): Flow<List<Program>> = combine(
        q.selectPrograms().asFlow().mapToList(io),
        q.selectDays().asFlow().mapToList(io),
        q.selectSlots().asFlow().mapToList(io),
    ) { programs, days, slots ->
        val slotsByDay = slots.groupBy { it.day_id }
        val daysByProgram = days.groupBy { it.program_id }
        programs.map { p ->
            Program(
                id = p.id,
                name = p.name,
                description = p.description,
                goals = ProgramCodec.decodeGoals(p.goals),
                level = Experience.entries.firstOrNull { it.name == p.level } ?: Experience.BEGINNER,
                daysPerWeek = p.days_per_week.toInt(),
                isBuiltIn = false,
                days = (daysByProgram[p.id] ?: emptyList()).sortedBy { it.position }.map { d ->
                    ProgramDay(
                        id = d.id,
                        name = d.name,
                        slots = (slotsByDay[d.id] ?: emptyList()).sortedBy { it.position }.map { s ->
                            ExerciseSlot(
                                exerciseId = s.exercise_id,
                                sets = s.sets.toInt(),
                                reps = ProgramCodec.decodeReps(s.reps) ?: RepTarget.Fixed(5),
                                lastSetAmrap = s.last_set_amrap != 0L,
                                progression = ProgramCodec.decodeRule(s.progression),
                                note = s.note,
                            )
                        },
                    )
                },
            )
        }
    }.flowOn(io)

    /** Built-ins first, then the user's programs by name. */
    fun observePrograms(): Flow<List<Program>> = observeCustomPrograms().map { custom ->
        ProgramCatalogue.builtIn + custom.sortedBy { it.name.lowercase() }
    }

    fun observeActiveProgramId(): Flow<String?> = settings.observe(KEY_ACTIVE_PROGRAM).map { it?.ifBlank { null } }

    /** When each program was last started over, by id. One never started over counts every session logged from it. */
    fun observeStarts(): Flow<Map<String, LocalDateTime>> = settings.observe(KEY_PROGRAM_STARTS).map { decodeStarts(it) }

    fun observeProfile(): Flow<GoalProfile?> = settings.observe(KEY_GOAL_PROFILE).map { GoalProfile.decode(it) }

    /** True once the user has finished or skipped onboarding. Emits false while the key is absent. */
    fun observeOnboardingDone(): Flow<Boolean> = settings.observe(KEY_ONBOARDING).map { it == "1" }

    fun observeState(): Flow<ProgramState> = combine(observeProfile(), observePrograms(), observeActiveProgramId(), observeStarts()) { profile, programs, active, starts ->
        ProgramState(profile, programs, active, starts)
    }

    /** Makes [id] the active program, carrying on from where it was left. */
    suspend fun setActive(id: String?) = settings.set(KEY_ACTIVE_PROGRAM, id ?: "")

    /**
     * Makes [id] the active program from its first day, week 1 of cycle 1. The sessions logged from it
     * before stay in the history, and the weights they reached still seed the next ones.
     */
    suspend fun startOver(id: String) {
        settings.set(KEY_PROGRAM_STARTS, encodeStarts(observeStarts().first() + (id to nowDateTime())))
        setActive(id)
    }
    suspend fun setProfile(profile: GoalProfile) = settings.set(KEY_GOAL_PROFILE, profile.encode())
    suspend fun markOnboardingDone() = settings.set(KEY_ONBOARDING, "1")
    suspend fun resetOnboarding() = settings.set(KEY_ONBOARDING, "")

    /** Writes a custom program whole. Day ids must already be set (see [duplicate] / [newDayId]). */
    suspend fun upsert(program: Program) = withContext(io) {
        require(!program.isBuiltIn) { "Built-in programs are read-only; duplicate first." }
        db.transaction {
            val createdAt = q.selectProgramCreatedAt(program.id).executeAsOneOrNull() ?: now()
            writeProgramRows(q, program, createdAt)
        }
    }

    /** Deletes a custom program; clears the active choice if it pointed at it. */
    suspend fun delete(id: String) {
        withContext(io) {
            db.transaction {
                q.deleteSlotsForProgram(id)
                q.deleteDaysForProgram(id)
                q.deleteProgram(id)
            }
        }
        if (observeActiveProgramId().first() == id) setActive(null)
        val starts = observeStarts().first()
        if (id in starts) settings.set(KEY_PROGRAM_STARTS, encodeStarts(starts - id))
    }

    /** An editable copy with fresh ids. Pure: the caller decides whether to save it. */
    fun duplicate(source: Program, newName: String = "${source.name} (copy)"): Program {
        val id = newProgramId()
        return source.copy(
            id = id,
            name = newName,
            isBuiltIn = false,
            days = source.days.mapIndexed { i, d -> d.copy(id = newDayId(id, i)) },
        )
    }

    companion object {
        const val KEY_ACTIVE_PROGRAM = "active_program"
        const val KEY_GOAL_PROFILE = "goal_profile"
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_PROGRAM_STARTS = "program_starts"

        fun newProgramId(): String = "custom_" + Clock.System.now().toEpochMilliseconds()
        fun newDayId(programId: String, index: Int): String = "$programId/d${index}_" + Clock.System.now().toEpochMilliseconds().toString(36)
        private fun now(): String = nowDateTime().toString()
        private fun nowDateTime(): LocalDateTime = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault())

        /** One `id=timestamp` line per program. An id never holds a line break; a timestamp never holds `=`. */
        internal fun encodeStarts(starts: Map<String, LocalDateTime>): String =
            starts.entries.joinToString("\n") { (id, at) -> "$id=$at" }

        internal fun decodeStarts(raw: String?): Map<String, LocalDateTime> =
            raw.orEmpty().lines().mapNotNull { line ->
                val split = line.lastIndexOf('=')
                if (split <= 0) return@mapNotNull null
                runCatching { LocalDateTime.parse(line.substring(split + 1)) }.getOrNull()?.let { line.substring(0, split) to it }
            }.toMap()
    }
}
