package app.gains.connectors

import app.gains.csv.CsvFormatException
import app.gains.csv.CsvReader
import app.gains.csv.ParsedCsv
import app.gains.domain.WeightUnit

/** Options the user can set on the import screen. */
data class ImportOptions(
    /** Unit for weight columns that do not state their unit. */
    val weightUnit: WeightUnit = WeightUnit.KG,
    /** Durations longer than this are treated as a timer left running. */
    val maxPlausibleDurationMinutes: Int = 4 * 60,
)

/**
 * A source of training history. Today every connector reads a CSV export; later ones
 * may talk to an API or a device. Each connector recognises its own file layout.
 */
interface ImportConnector {
    val id: String
    val displayName: String
    /** Unit the connector assumes for weights when the file does not say; null if the file always states it. */
    val defaultWeightUnit: WeightUnit?

    /** How confident the connector is that [header] is its format: 0 = no, higher wins. */
    fun match(header: List<String>): Int
    fun parse(text: String, options: ImportOptions): ParsedCsv
}

/** Registry of connectors, in priority order. */
object Connectors {
    val all: List<ImportConnector> = listOf(LiftoffConnector, StrongConnector, HevyConnector, GenericCsvConnector)

    fun byId(id: String): ImportConnector? = all.firstOrNull { it.id == id }

    /** Picks the connector that recognises the file's header best. */
    fun detect(text: String): ImportConnector {
        val header = CsvReader.parse(text.take(4000)).firstOrNull()?.fields?.map { it.trim() }
            ?: throw CsvFormatException("The file is empty.")
        return all.map { it to it.match(header) }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
            ?: throw CsvFormatException("Not a recognised workout export. Expected columns for date, exercise, weight and reps.")
    }
}
