package app.gains.importer

import app.gains.csv.ParsedCsv
import app.gains.csv.RawSession

/** Combines several parsed exports into one, keeping each calendar-date session once. */
object MultiFileMerger {
    data class Result(val csv: ParsedCsv, val sessionsInSeveralFiles: Int)

    fun merge(files: List<ParsedCsv>): Result {
        if (files.size == 1) return Result(files.single(), 0)
        val byId = LinkedHashMap<String, RawSession>()
        var overlaps = 0
        for (csv in files) {
            for (session in csv.sessions) {
                val previous = byId[session.id]
                if (previous == null) {
                    byId[session.id] = session
                } else {
                    overlaps++
                    // Exports made later can carry sets added after the earlier export: keep the fuller copy.
                    if (session.setCount > previous.setCount) byId[session.id] = session
                }
            }
        }
        return Result(
            ParsedCsv(
                sessions = byId.values.sortedBy { it.timestamp },
                skipped = files.flatMap { it.skipped },
                rowCount = files.sumOf { it.rowCount },
            ),
            sessionsInSeveralFiles = overlaps,
        )
    }
}
