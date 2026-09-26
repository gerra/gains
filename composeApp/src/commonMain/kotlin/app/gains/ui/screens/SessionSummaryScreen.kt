package app.gains.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.rememberSaveable
import app.gains.ui.theme.LocalReduceMotion
import app.gains.ui.theme.Motion
import app.gains.ui.theme.enterOnce
import app.gains.ui.theme.fadeThrough
import kotlin.math.roundToInt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.gains.analysis.AchievementStatus
import app.gains.analysis.Achievements
import app.gains.analysis.Dates
import app.gains.analysis.Format
import app.gains.analysis.Level
import app.gains.analysis.NearMiss
import app.gains.analysis.Record
import app.gains.analysis.Records
import app.gains.analysis.Scoring
import app.gains.analysis.SessionScore
import app.gains.analysis.TrainingData
import app.gains.analysis.VolumeAnalyzer
import app.gains.data.BodyweightRepository
import app.gains.data.ProgramRepository
import app.gains.data.SessionRepository
import app.gains.data.SettingsRepository
import app.gains.domain.BodyweightEntry
import app.gains.domain.Exercise
import app.gains.domain.MuscleGroup
import app.gains.domain.Session
import app.gains.domain.Units
import app.gains.domain.WeightUnit
import app.gains.platform.PhotoPicker
import app.gains.platform.decodeImage
import app.gains.platform.shrinkPhoto
import app.gains.resources.Res
import app.gains.resources.*
import app.gains.ui.ScreenModel
import app.gains.ui.charts.BodyMap
import app.gains.ui.charts.BodyMapLegend
import app.gains.ui.components.ChooserRow
import app.gains.ui.components.Dp16
import app.gains.ui.components.DurationPickerSheet
import app.gains.ui.components.GainsCard
import app.gains.ui.components.Meter
import app.gains.ui.components.MetricTile
import app.gains.ui.components.Pill
import app.gains.ui.components.PrimaryButton
import app.gains.ui.components.RoundedIconBox
import app.gains.ui.components.SectionHeader
import app.gains.ui.components.WeightPickerSheet
import app.gains.ui.components.WheelWeight
import app.gains.ui.i18n.*
import app.gains.ui.inject
import app.gains.ui.rememberScreenModel
import app.gains.ui.theme.GainsColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.stringResource

internal data class SummaryState(
    val loading: Boolean = true,
    /** The workout is no longer in the database (deleted from another screen). */
    val missing: Boolean = false,
    val title: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val durationMinutes: Int? = null,
    val exerciseCount: Int = 0,
    val workingSets: Int = 0,
    val volumeKg: Double = 0.0,
    /** Working sets credited to each muscle group by this one workout; what the body is shaded with. */
    val muscles: Map<MuscleGroup, Double> = emptyMap(),
    val unit: WeightUnit = WeightUnit.KG,
    /** The most recent body weight on record, which the wheel starts from. */
    val lastWeight: BodyweightEntry? = null,
    /** What the wheel holds, as the weight picker's text, in display units. */
    val weightText: String = "",
    /** A body weight was written for the workout's day. */
    val weightSaved: Boolean = false,
    val caption: String = "",
    val photo: ImageBitmap? = null,
    /** A photo is being shrunk and stored, or read back. */
    val photoBusy: Boolean = false,
    /** The records this workout set, in the order of its exercises. */
    val records: List<Record> = emptyList(),
    /** With no records, the lift that came closest to one, if any did. */
    val nearMiss: NearMiss? = null,
    /** The score, the level and the achievements are shown; off in Settings leaves the records alone. */
    val trophiesOn: Boolean = true,
    val score: SessionScore? = null,
    /** Where the running total stands with this workout in it. */
    val level: Level = Scoring.level(0),
    /** The achievements this workout earned. */
    val unlocked: List<AchievementStatus> = emptyList(),
    val exercisesById: Map<String, Exercise> = emptyMap(),
) {
    val weight: Double get() = WheelWeight.parse(weightText).value
    /** The heaviest group decides the shading, so one workout's spread reads even at a few sets a muscle. */
    val maxSets: Double get() = (muscles.values.maxOrNull() ?: 0.0).coerceAtLeast(4.0)
    /** Muscle groups this workout credited, most sets first. */
    val trained: List<Pair<MuscleGroup, Double>> get() = muscles.entries.sortedByDescending { it.value }.map { it.key to it.value }
}

/**
 * What one stored workout came to. Everything it offers changes the session that is already in the
 * database: the duration and the caption are written as they are changed, the photo as it is picked,
 * and the body weight only when it is saved, since that is a record of its own.
 */
internal class SessionSummaryModel(
    private val sessionId: String,
    private val texts: Texts,
    private val sessions: SessionRepository = inject(),
    private val bodyweight: BodyweightRepository = inject(),
    private val programs: ProgramRepository = inject(),
    private val settings: SettingsRepository = inject(),
    private val trainingData: TrainingData = inject(),
) : ScreenModel() {
    private val _state = MutableStateFlow(SummaryState())
    val state: StateFlow<SummaryState> = _state

    private var writeJob: Job? = null

    init {
        scope.launch {
            val snapshot = trainingData.snapshot.first()
            val session = snapshot.sessions.firstOrNull { it.id == sessionId }
            if (session == null) {
                _state.value = SummaryState(loading = false, missing = true)
                return@launch
            }
            val unit = settings.observeUnit().first()
            val trophiesOn = settings.observeTrophies().first()
            val goal = programs.observeState().first().weeklyGoal
            val entries = bodyweight.observe().first()
            val onDay = entries.firstOrNull { it.date == session.date }
            val last = onDay ?: entries.maxByOrNull { it.date }
            // The records and the score are judged against everything before this workout, and the
            // achievements against everything up to and including it: one pass over the history each.
            val trophies = withContext(Dispatchers.Default) {
                val records = Records.forSession(session, snapshot.sessions, snapshot.exercisesById)
                val statuses = if (trophiesOn) Achievements.evaluate(snapshot.sessions, snapshot.exercisesById, unit, goal) else emptyList()
                val total = if (trophiesOn) Scoring.total(Scoring.sessions(snapshot.sessions, snapshot.exercisesById).values) else 0
                Trophies(
                    records = records,
                    nearMiss = if (records.isEmpty()) Records.nearMiss(session, snapshot.sessions, snapshot.exercisesById) else null,
                    score = Scoring.session(session, records),
                    level = Scoring.level(total),
                    unlocked = Achievements.earnedIn(statuses, session.id),
                )
            }
            _state.value = SummaryState(
                records = trophies.records,
                nearMiss = trophies.nearMiss,
                trophiesOn = trophiesOn,
                score = trophies.score,
                level = trophies.level,
                unlocked = trophies.unlocked,
                exercisesById = snapshot.exercisesById,
                loading = false,
                title = title(session),
                date = session.date,
                time = LocalTime(session.timestamp.hour, session.timestamp.minute),
                durationMinutes = session.durationMinutes,
                exerciseCount = session.exercises.size,
                workingSets = session.workingSetCount,
                volumeKg = session.exercises.sumOf { e -> e.workingSets.sumOf { it.volumeKg } },
                muscles = VolumeAnalyzer.sessionSets(session, snapshot.exercisesById),
                unit = unit,
                lastWeight = last,
                weightText = last?.let { Format.weightValue(it.weightKg, unit) } ?: "",
                weightSaved = onDay != null,
                caption = session.caption.orEmpty(),
            )
            if (session.hasPhoto) loadPhoto()
            writeWhileEditing()
        }
    }

    private data class Trophies(val records: List<Record>, val nearMiss: NearMiss?, val score: SessionScore, val level: Level, val unlocked: List<AchievementStatus>)

    /** "GZCLP · A1" for a workout started from a program day, else the plain word. */
    private suspend fun title(session: Session): String {
        val ref = session.program ?: return texts.get(Res.string.workout)
        val program = programs.observePrograms().first().firstOrNull { it.id == ref.programId } ?: return texts.get(Res.string.workout)
        val day = program.day(ref.dayId) ?: return program.resolvedName(texts)
        return "${program.resolvedName(texts)} · ${day.resolvedName(texts)}"
    }

    private fun loadPhoto() {
        scope.launch {
            update { it.copy(photoBusy = true) }
            val bytes = sessions.photo(sessionId)
            val bitmap = bytes?.let { withContext(Dispatchers.Default) { runCatching { decodeImage(it) }.getOrNull() } }
            update { it.copy(photo = bitmap, photoBusy = false) }
        }
    }

    /**
     * Writes the duration and the caption shortly after they stop changing, so that leaving the
     * screen at any moment keeps what was typed without a write per keystroke.
     */
    private fun writeWhileEditing() {
        writeJob = scope.launch {
            _state.map { it.durationMinutes to it.caption }.distinctUntilChanged().collectLatest { (minutes, caption) ->
                delay(WRITE_DELAY_MS)
                withContext(NonCancellable) { sessions.updateSummary(sessionId, minutes, caption) }
            }
        }
    }

    private fun update(f: (SummaryState) -> SummaryState) { _state.value = f(_state.value) }

    fun setDuration(minutes: Int?) = update { it.copy(durationMinutes = minutes?.takeIf { m -> m > 0 }) }

    fun setCaption(caption: String) = update { it.copy(caption = caption) }

    /** The wheel moved: the weight is not on record until Save is pressed. */
    fun setWeightText(text: String) = update { it.copy(weightText = text, weightSaved = false) }

    /** Records the body weight against the day the workout was on. */
    fun saveWeight() {
        val s = _state.value
        val date = s.date ?: return
        if (s.weight <= 0.0) return
        scope.launch {
            bodyweight.upsert(BodyweightEntry(date, Units.fromDisplay(s.weight, s.unit)))
            update { it.copy(weightSaved = true) }
        }
    }

    /** A picked photo, shrunk to something a database can hold, or null to drop the one there is. */
    fun setPhoto(bytes: ByteArray?) {
        scope.launch {
            update { it.copy(photoBusy = true) }
            if (bytes == null) {
                sessions.setPhoto(sessionId, null)
                update { it.copy(photo = null, photoBusy = false) }
                return@launch
            }
            val stored = withContext(Dispatchers.Default) { runCatching { shrinkPhoto(bytes) }.getOrDefault(bytes) }
            val bitmap = withContext(Dispatchers.Default) { runCatching { decodeImage(stored) }.getOrNull() }
            if (bitmap == null) { update { it.copy(photoBusy = false) }; return@launch }
            sessions.setPhoto(sessionId, stored)
            update { it.copy(photo = bitmap, photoBusy = false) }
        }
    }

    override fun onCleared() {
        // The debounce may still be waiting; the last duration and caption must reach the database anyway.
        val pending = writeJob
        val s = _state.value
        val write = !s.loading && !s.missing
        super.onCleared()
        if (write) flushScope.launch { pending?.cancel(); sessions.updateSummary(sessionId, s.durationMinutes, s.caption) }
    }

    companion object {
        const val WRITE_DELAY_MS = 300L

        /** Outlives the screen: the flush on the way out must not be cancelled with it. */
        private val flushScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/**
 * The screen a workout ends on: what it trained, drawn on the body, with the day's body weight, the
 * duration and a caption and photo to remember it by. Everything here edits the session already
 * stored, so leaving at any point keeps what was entered.
 */
@Composable
internal fun SessionSummaryScreen(sessionId: String, picker: PhotoPicker, onDone: () -> Unit) {
    val texts = rememberTexts()
    val model = rememberScreenModel(sessionId) { SessionSummaryModel(sessionId, texts) }
    val state by model.state.collectAsState()
    val palette = GainsColors.palette
    var durationPickerOpen by remember { mutableStateOf(false) }
    var weightPickerOpen by remember { mutableStateOf(false) }
    if (state.loading) return
    if (state.missing) { onDone(); return }
    val today = Dates.today()
    val unit = state.unit
    val fieldColors = OutlinedTextFieldDefaults.colors(focusedBorderColor = palette.volt, unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    val reduce = LocalReduceMotion.current
    // The end of a workout is the one screen that makes an entrance: the sections rise in one after
    // another and the three figures count up to what the session came to. Once only; coming back
    // to the summary later shows it as it is.
    var counted by rememberSaveable { mutableStateOf(reduce) }
    val count = remember { Animatable(if (counted) 1f else 0f) }
    val revealSpec = Motion.reveal<Float>()
    LaunchedEffect(Unit) {
        if (counted) return@LaunchedEffect
        count.animateTo(1f, revealSpec)
        counted = true
    }
    val p = count.value

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
        item { Column(Modifier.enterOnce(0)) {
            Text(stringResource(Res.string.workout_logged), style = MaterialTheme.typography.labelSmall, color = palette.volt)
            Spacer(Modifier.height(4.dp))
            Text(state.title, style = MaterialTheme.typography.headlineLarge)
            Text(
                state.date?.let { d -> dateWithWeekday(d, today) + (state.time?.let { " · " + clockText(it) } ?: "") } ?: "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricTile(
                    stringResource(Res.string.duration),
                    state.durationMinutes?.let { minutesText(if (counted) it else (it * p).roundToInt()) } ?: stringResource(Res.string.not_timed),
                    Modifier.weight(1f),
                    caption = stringResource(Res.string.tap_to_change),
                    accent = palette.volt,
                    onClick = { durationPickerOpen = true },
                    roll = counted,
                )
                MetricTile(
                    stringResource(Res.string.exercises_section), (if (counted) state.exerciseCount else (state.exerciseCount * p).roundToInt()).toString(),
                    Modifier.weight(1f), caption = setsText(state.workingSets), roll = counted,
                )
                MetricTile(
                    stringResource(Res.string.total_volume), Format.weightValue(if (counted) state.volumeKg else state.volumeKg * p, unit),
                    Modifier.weight(1f), caption = unit.label(), roll = counted,
                )
            }
        } }
        item { Column(Modifier.enterOnce(1)) {
            RecordsSection(state, today)
        } }
        if (state.trophiesOn) {
            item { Column(Modifier.enterOnce(2)) {
                state.score?.let { ScoreSection(it, state.level, if (counted) 1f else p) }
            } }
            if (state.unlocked.isNotEmpty()) {
                item { Column(Modifier.enterOnce(3)) {
                    UnlockedSection(state.unlocked, unit, state.exercisesById)
                } }
            }
        }
        item { Column(Modifier.enterOnce(4)) {
            SectionHeader(stringResource(Res.string.bodyweight_title))
            // One number and one word to put it on record: the wheel on the left, Save beside it.
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    ChooserRow(
                        stringResource(Res.string.weight_label),
                        if (state.weight > 0) Format.number(state.weight, 2) + " " + unit.label() else stringResource(Res.string.not_set),
                        onClick = { weightPickerOpen = true },
                        modifier = Modifier.weight(1f),
                        muted = state.weight <= 0,
                    )
                    AnimatedContent(state.weightSaved, transitionSpec = { fadeThrough(reduce) }, label = "weight-saved") { saved ->
                        if (saved) {
                            Text(
                                stringResource(Res.string.saved),
                                Modifier.padding(start = 10.dp),
                                style = MaterialTheme.typography.labelMedium, color = palette.volt,
                            )
                        } else {
                            TextButton(onClick = model::saveWeight, enabled = state.weight > 0) {
                                Text(stringResource(Res.string.save), color = if (state.weight > 0) palette.volt else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                Text(
                    state.lastWeight?.let { stringResource(Res.string.last_weight_on, weightText(it.weightKg, unit, 1), dateContextual(it.date, today)) }
                        ?: stringResource(Res.string.no_bodyweight_yet),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } }
        item { Column(Modifier.enterOnce(5)) {
            SectionHeader(stringResource(Res.string.caption_and_photo))
            // One row, as Liftoff has it: a small picture on the left and the line about the
            // session beside it, so the two read as one note rather than two sections.
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    PhotoThumbnail(
                        state.photo, state.photoBusy,
                        onPick = { picker.pick(model::setPhoto) },
                        onRemove = { model.setPhoto(null) },
                    )
                    Spacer(Modifier.width(12.dp))
                    OutlinedTextField(
                        state.caption,
                        model::setCaption,
                        placeholder = { Text(stringResource(Res.string.caption_placeholder)) },
                        modifier = Modifier.weight(1f).height(PhotoHeight),
                        colors = fieldColors,
                        shape = MaterialTheme.shapes.medium,
                        textStyle = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                    )
                }
            }
        } }
        item { Column(Modifier.enterOnce(6)) {
            SectionHeader(stringResource(Res.string.muscles_trained))
            GainsCard(Modifier.fillMaxWidth(), contentPadding = Dp16.Tight) {
                if (state.muscles.isEmpty()) {
                    Text(stringResource(Res.string.no_muscles_trained), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    BodyMap(state.muscles, Modifier.fillMaxWidth(), maxSets = state.maxSets)
                    BodyMapLegend(state.maxSets)
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Spacer(Modifier.height(6.dp))
                    for ((group, sets) in state.trained) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(group.label(), Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
                            Meter((sets / state.maxSets).toFloat(), palette.volt, Modifier.weight(1f))
                            Spacer(Modifier.width(10.dp))
                            Text(Format.number(sets, 1), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
            }
        } }
        item { Column(Modifier.enterOnce(7)) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton(stringResource(Res.string.done), onDone, Modifier.fillMaxWidth())
        } }
    }

    if (durationPickerOpen) DurationPickerSheet(state.durationMinutes, onPick = model::setDuration, onDismiss = { durationPickerOpen = false })
    if (weightPickerOpen) WeightPickerSheet(
        value = state.weightText, unit = unit, title = stringResource(Res.string.bodyweight_title),
        subtitle = state.date?.let { dateContextual(it, today) },
        onPick = model::setWeightText, onDismiss = { weightPickerOpen = false },
        clearable = false, steps = WheelWeight.bodyweightSteps(unit),
    )
}

/**
 * The records the workout set, each named exactly — the kind, the set that did it and what it
 * beat — grouped by lift. With none, one quiet line, and the lift that came closest if one did:
 * the next target named rather than the miss dwelt on.
 */
@Composable
private fun RecordsSection(state: SummaryState, today: LocalDate) {
    val palette = GainsColors.palette
    val unit = state.unit
    SectionHeader(stringResource(Res.string.records_section))
    if (state.records.isEmpty()) {
        Text(stringResource(Res.string.no_records_this_time), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val miss = state.nearMiss
        val exercise = miss?.let { state.exercisesById[it.exerciseId] }
        if (miss != null && exercise != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(Res.string.record_near_miss, exercise.displayName(), weightText(miss.liftedKg, unit), weightText(miss.shortByKg, unit)),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    GainsCard(Modifier.fillMaxWidth(), brush = palette.heroBrush()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundedIconBox(palette.volt) { Text("★", style = MaterialTheme.typography.titleLarge, color = palette.volt) }
            Spacer(Modifier.width(14.dp))
            Text(recordsText(state.records.size), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
        }
        for ((exerciseId, records) in state.records.groupBy { it.exerciseId }) {
            val exercise = state.exercisesById[exerciseId] ?: continue
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(10.dp))
            Text(exercise.displayName(), style = MaterialTheme.typography.titleMedium)
            for (record in records) {
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Text(record.kind.label(), Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(recordText(record, exercise.modality, unit), style = MaterialTheme.typography.titleSmall, color = palette.volt)
                        Text(
                            stringResource(Res.string.record_was, recordValueText(record.kind, record.previous, unit)) + " · " + dateContextual(record.previousDate, today),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What the workout scored and from what, counted up with the other figures, then where the running
 * total now stands. The three parts are shown as they are so the number is never a mystery.
 */
@Composable
private fun ScoreSection(score: SessionScore, level: Level, p: Float) {
    val palette = GainsColors.palette
    SectionHeader(stringResource(Res.string.score_section))
    GainsCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("+" + (score.total * p).roundToInt(), style = MaterialTheme.typography.displayMedium, color = palette.volt)
            Spacer(Modifier.width(8.dp))
            Text(pointsWord(score.total), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ScorePart(stringResource(Res.string.score_showed_up), score.showedUp)
            ScorePart(stringResource(Res.string.score_work), score.work)
            ScorePart(stringResource(Res.string.score_records), score.records)
        }
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.level_label, level.level), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(stringResource(Res.string.points_in_total, pointsText(level.points)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        Meter(level.fraction.toFloat(), palette.volt, Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text(stringResource(Res.string.level_to_next, pointsText(level.toNext), level.level + 1), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScorePart(label: String, points: Int) {
    Column {
        Text("+$points", style = MaterialTheme.typography.titleMedium, color = if (points > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The achievements this workout earned, each with its badge, name and what it was for. */
@Composable
private fun UnlockedSection(unlocked: List<AchievementStatus>, unit: WeightUnit, exercisesById: Map<String, Exercise>) {
    val palette = GainsColors.palette
    SectionHeader(stringResource(Res.string.unlocked))
    GainsCard(Modifier.fillMaxWidth()) {
        unlocked.forEachIndexed { i, status ->
            if (i > 0) Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(status.achievement.track, lit = true)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(achievementTitle(status.achievement, exercisesById), style = MaterialTheme.typography.titleMedium)
                    Text(achievementTier(status.achievement, unit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(stringResource(Res.string.unlocked), palette.volt, filled = true)
            }
        }
    }
}

/** A passport-sized picture: enough to recognise the session by, small enough to sit on one row. */
private val PhotoWidth = 72.dp
private val PhotoHeight = 92.dp

/**
 * The workout's picture beside its caption: tap it to choose or change one, and the cross in its
 * corner to take it off again. Empty, it is the place the photo goes and says so.
 */
@Composable
private fun PhotoThumbnail(photo: ImageBitmap?, busy: Boolean, onPick: () -> Unit, onRemove: () -> Unit) {
    val palette = GainsColors.palette
    val shape = MaterialTheme.shapes.medium
    val reduce = LocalReduceMotion.current
    // A picture chosen or taken off fades in over the empty place, rather than cutting.
    AnimatedContent(photo, Modifier.size(PhotoWidth, PhotoHeight), transitionSpec = { fadeThrough(reduce) }, label = "photo") { photo ->
    Box(Modifier.size(PhotoWidth, PhotoHeight)) {
        if (photo == null) {
            val addPhoto = stringResource(Res.string.add_photo)
            Box(
                Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(onClick = onPick)
                    .semantics { contentDescription = addPhoto },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // A plain plus: the bundled font has no fullwidth one and draws it as a box.
                    Text("+", style = MaterialTheme.typography.titleLarge, color = palette.volt)
                    Text(
                        if (busy) stringResource(Res.string.adding_photo) else stringResource(Res.string.photo_pill),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return@Box
        }
        Image(
            photo,
            stringResource(Res.string.workout_photo),
            Modifier.fillMaxSize().clip(shape).clickable(onClick = onPick),
            contentScale = ContentScale.Crop,
        )
        val remove = stringResource(Res.string.remove_photo)
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .clickable(onClick = onRemove)
                .semantics { contentDescription = remove },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Default.Close, null, tint = palette.coral, modifier = Modifier.size(14.dp)) }
    }
    }
}

/** "18:05" */
private fun clockText(time: LocalTime): String =
    "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"
