package app.gains.ui.i18n

import androidx.compose.runtime.Composable
import app.gains.domain.Exercise
import app.gains.domain.Program
import app.gains.domain.ProgramDay
import app.gains.resources.Res
import app.gains.resources.allStringResources
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/*
 * The catalogues declare their exercises, programs, days and slot notes in English, and that is
 * what the database holds. On the way to the screen a built-in one is looked up in the string
 * resources by a key derived from its id (`exercise_bench_press`, `program_gzclp`,
 * `program_gzclp_description`, `day_workout_a`) or, for a slot note, by its English text; a
 * custom one is shown as typed. Each name has a composable form for screens and a suspend form
 * for screen models, which live outside the composition.
 */

@OptIn(ExperimentalResourceApi::class)
private fun exerciseKey(exercise: Exercise): StringResource? = if (exercise.isBuiltIn) Res.allStringResources["exercise_${exercise.id}"] else null
@OptIn(ExperimentalResourceApi::class)
private fun programKey(program: Program): StringResource? = if (program.isBuiltIn) Res.allStringResources["program_${program.id}"] else null
@OptIn(ExperimentalResourceApi::class)
private fun programDescriptionKey(program: Program): StringResource? = if (program.isBuiltIn) Res.allStringResources["program_${program.id}_description"] else null
/** A day of a built-in program, or a custom day that kept a built-in name; "A1"-style names have no entry and stay as they are. */
@OptIn(ExperimentalResourceApi::class)
private fun dayKey(day: ProgramDay): StringResource? = Res.allStringResources["day_" + day.name.lowercase().replace(' ', '_')]
@OptIn(ExperimentalResourceApi::class)
private fun slotNoteKey(note: String): StringResource? = SlotNoteKeys.keys[note]?.let { Res.allStringResources[it] }

@Composable
internal fun Exercise.displayName(): String = exerciseKey(this)?.let { stringResource(it) } ?: name

@Composable
internal fun Program.displayName(): String = programKey(this)?.let { stringResource(it) } ?: name

@Composable
internal fun Program.displayDescription(): String = programDescriptionKey(this)?.let { stringResource(it) } ?: description

@Composable
internal fun ProgramDay.displayName(): String = dayKey(this)?.let { stringResource(it) } ?: name

@Composable
internal fun slotNoteText(note: String): String = slotNoteKey(note)?.let { stringResource(it) } ?: note

internal suspend fun Exercise.resolvedName(): String = exerciseKey(this)?.let { getString(it) } ?: name
internal suspend fun Program.resolvedName(): String = programKey(this)?.let { getString(it) } ?: name
internal suspend fun ProgramDay.resolvedName(): String = dayKey(this)?.let { getString(it) } ?: name

/** The slot notes of the built-in programs by their English text, which a duplicated program carries with it. */
internal object SlotNoteKeys {
    val keys: Map<String, String> = mapOf(
        "Lat pulldown if you cannot do chin-ups yet." to "slot_note_1",
        "T1: last set as many reps as possible." to "slot_note_2",
        "T2" to "slot_note_3",
        "T3: add weight once the last set reaches 25 reps." to "slot_note_4",
        "5/3/1 wave on a training max of 90% of your 1RM: week 1 65/75/85% ×5, week 2 70/80/90% ×3, week 3 75/85/95% ×5/3/1; last set as many as possible. Then 5×5 at the first working weight. Add 2.5 kg upper / 5 kg lower to the training max after each 3-week cycle." to "slot_note_5",
        "Push: 50–100 reps total." to "slot_note_6",
        "Pull: 50–100 reps total." to "slot_note_7",
        "Single-leg / core: 50–100 reps total." to "slot_note_8",
        "Or lat pulldown." to "slot_note_9",
        "Or chest supported row." to "slot_note_10",
        "Superset with lateral raises." to "slot_note_11",
        "Three sets after each triceps exercise." to "slot_note_12",
        "Seconds per set." to "slot_note_13",
        "Add weight once 4×8 is easy." to "slot_note_14",
        "Per leg." to "slot_note_15",
        "Pair 1 with squats. Progression: scapular pulls → negatives → pull-ups → weighted." to "slot_note_16",
        "Pair 1. Progression: assisted → bodyweight → split → Bulgarian → pistol." to "slot_note_17",
        "Pair 2 with hinge. Progression: support hold → negatives → dips → weighted." to "slot_note_18",
        "Pair 2. Progression: Romanian deadlift → single-leg → banded/weighted." to "slot_note_19",
        "Pair 3 with push-ups. Progression: vertical → incline → horizontal → wide → weighted." to "slot_note_20",
        "Pair 3. Progression: incline → full → diamond → pseudo planche." to "slot_note_21",
        "Core triplet, anti-extension: deadbug → hanging knee raise → hanging leg raise." to "slot_note_22",
        "Core triplet, anti-rotation." to "slot_note_23",
        "Core triplet, extension: reverse hyper → arch hold / superman." to "slot_note_24",
    )
}
