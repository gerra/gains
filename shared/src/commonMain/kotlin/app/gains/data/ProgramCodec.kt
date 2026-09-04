package app.gains.data

import app.gains.domain.Equipment
import app.gains.domain.Goal
import app.gains.domain.ProgressionRule
import app.gains.domain.RepTarget
import app.gains.domain.SetsReps

/**
 * Compact text forms for program fields stored in SQLite. Decoding is tolerant: anything
 * unparseable falls back to a safe default rather than failing the whole row.
 */
object ProgramCodec {
    /** "5", "8-12", "5+" */
    fun encodeReps(reps: RepTarget): String = reps.label

    fun decodeReps(raw: String): RepTarget? {
        val s = raw.trim().replace('–', '-').replace('—', '-').replace(" ", "")
        if (s.isEmpty()) return null
        if (s.endsWith("+")) return s.dropLast(1).toIntOrNull()?.takeIf { it > 0 }?.let { RepTarget.Amrap(it) }
        val range = s.split('-')
        if (range.size == 2) {
            val a = range[0].toIntOrNull() ?: return null
            val b = range[1].toIntOrNull() ?: return null
            if (a <= 0 || b < a) return null
            return if (a == b) RepTarget.Fixed(a) else RepTarget.Range(a, b)
        }
        return s.toIntOrNull()?.takeIf { it > 0 }?.let { RepTarget.Fixed(it) }
    }

    /** "" | "linear:2.5/5" | "double:8-12:2.5/5" | "ladder:5x3+|6x2+|10x1+:5/10" */
    fun encodeRule(rule: ProgressionRule): String = when (rule) {
        ProgressionRule.None -> ""
        is ProgressionRule.Linear -> "linear:${num(rule.stepKg)}/${num(rule.stepLbs)}"
        is ProgressionRule.DoubleProgression -> "double:${rule.min}-${rule.max}:${num(rule.stepKg)}/${num(rule.stepLbs)}"
        is ProgressionRule.StageLadder -> "ladder:" + rule.stages.joinToString("|") { "${it.sets}x${encodeReps(it.reps)}" } + ":${num(rule.stepKg)}/${num(rule.stepLbs)}"
    }

    fun decodeRule(raw: String?): ProgressionRule {
        if (raw.isNullOrBlank()) return ProgressionRule.None
        val parts = raw.split(':')
        fun steps(s: String): Pair<Double, Double>? {
            val (kg, lbs) = s.split('/').let { (it.getOrNull(0)?.toDoubleOrNull() ?: return null) to (it.getOrNull(1)?.toDoubleOrNull()) }
            return kg to (lbs ?: kg * 2)
        }
        return when (parts[0]) {
            "linear" -> steps(parts.getOrNull(1) ?: return ProgressionRule.None)?.let { (kg, lbs) -> ProgressionRule.Linear(kg, lbs) } ?: ProgressionRule.None
            "double" -> {
                val range = decodeReps(parts.getOrNull(1) ?: "") as? RepTarget.Range ?: return ProgressionRule.None
                val (kg, lbs) = steps(parts.getOrNull(2) ?: return ProgressionRule.None) ?: return ProgressionRule.None
                ProgressionRule.DoubleProgression(range.min, range.max, kg, lbs)
            }
            "ladder" -> {
                val stages = (parts.getOrNull(1) ?: return ProgressionRule.None).split('|').mapNotNull { stage ->
                    val (sets, reps) = stage.split('x', limit = 2).let { (it.getOrNull(0)?.toIntOrNull() ?: return@mapNotNull null) to (it.getOrNull(1) ?: return@mapNotNull null) }
                    decodeReps(reps)?.let { SetsReps(sets, it) }
                }
                if (stages.isEmpty()) return ProgressionRule.None
                val (kg, lbs) = steps(parts.getOrNull(2) ?: return ProgressionRule.None) ?: return ProgressionRule.None
                ProgressionRule.StageLadder(stages, kg, lbs)
            }
            else -> ProgressionRule.None
        }
    }

    fun encodeEquipment(equipment: Set<Equipment>): String = equipment.joinToString(",") { it.name }
    fun decodeEquipment(raw: String?): Set<Equipment> =
        raw.orEmpty().split(',').mapNotNull { t -> Equipment.entries.firstOrNull { it.name == t.trim() } }.toSet()

    fun encodeGoals(goals: Set<Goal>): String = goals.joinToString(",") { it.name }
    fun decodeGoals(raw: String?): Set<Goal> =
        raw.orEmpty().split(',').mapNotNull { t -> Goal.entries.firstOrNull { it.name == t.trim() } }.toSet()

    private fun num(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
}
