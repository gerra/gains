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
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock

/**
 * Built-in programs come from [ProgramCatalogue]; the user's own live in the program tables.
 * The goal profile, the active program and the onboarding flag are plain settings.
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

    fun observeProfile(): Flow<GoalProfile?> = settings.observe(KEY_GOAL_PROFILE).map { GoalProfile.decode(it) }

    /** True once the user has finished or skipped onboarding. Emits false while the key is absent. */
    fun observeOnboardingDone(): Flow<Boolean> = settings.observe(KEY_ONBOARDING).map { it == "1" }

    fun observeState(): Flow<ProgramState> = combine(observeProfile(), observePrograms(), observeActiveProgramId()) { profile, programs, active ->
        ProgramState(profile, programs, active)
    }

    suspend fun setActive(id: String?) = settings.set(KEY_ACTIVE_PROGRAM, id ?: "")
    suspend fun setProfile(profile: GoalProfile) = settings.set(KEY_GOAL_PROFILE, profile.encode())
    suspend fun markOnboardingDone() = settings.set(KEY_ONBOARDING, "1")
    suspend fun resetOnboarding() = settings.set(KEY_ONBOARDING, "")

    /** Writes a custom program whole. Day ids must already be set (see [duplicate] / [newDayId]). */
    suspend fun upsert(program: Program) = withContext(io) {
        require(!program.isBuiltIn) { "Built-in programs are read-only; duplicate first." }
        db.transaction {
            val createdAt = q.selectProgramCreatedAt(program.id).executeAsOneOrNull() ?: now()
            q.deleteSlotsForProgram(program.id)
            q.deleteDaysForProgram(program.id)
            q.upsertProgram(
                id = program.id, name = program.name, description = program.description,
                goals = ProgramCodec.encodeGoals(program.goals), level = program.level.name,
                days_per_week = program.daysPerWeek.toLong(), created_at = createdAt,
            )
            program.days.forEachIndexed { di, day ->
                q.insertDay(day.id, program.id, di.toLong(), day.name)
                day.slots.forEachIndexed { si, slot ->
                    q.insertSlot(
                        day_id = day.id, position = si.toLong(), exercise_id = slot.exerciseId,
                        sets = slot.sets.toLong(), reps = ProgramCodec.encodeReps(slot.reps),
                        last_set_amrap = if (slot.lastSetAmrap) 1L else 0L,
                        progression = ProgramCodec.encodeRule(slot.progression), note = slot.note,
                    )
                }
            }
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

        fun newProgramId(): String = "custom_" + Clock.System.now().toEpochMilliseconds()
        fun newDayId(programId: String, index: Int): String = "$programId/d${index}_" + Clock.System.now().toEpochMilliseconds().toString(36)
        private fun now(): String = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).toString()
    }
}
