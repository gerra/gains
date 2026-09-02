package app.gains.csv

/**
 * Minimal RFC 4180 reader: quoted fields, doubled-quote escapes, embedded commas
 * and line breaks inside quotes, CRLF or LF row terminators.
 */
object CsvReader {
    data class Record(val lineNumber: Int, val fields: List<String>)

    fun parse(text: String): List<Record> {
        val records = ArrayList<Record>()
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        var line = 1
        var recordStartLine = 1
        var fieldHadQuotes = false
        val n = text.length

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
            fieldHadQuotes = false
        }

        fun endRecord() {
            endField()
            // Ignore completely blank lines.
            if (!(fields.size == 1 && fields[0].isEmpty())) {
                records.add(Record(recordStartLine, ArrayList(fields)))
            }
            fields.clear()
            recordStartLine = line
        }

        // Skip a UTF-8 BOM if present.
        if (n > 0 && text[0] == '\uFEFF') i = 1

        while (i < n) {
            val c = text[i]
            if (inQuotes) {
                when {
                    c == '"' && i + 1 < n && text[i + 1] == '"' -> { field.append('"'); i += 2; continue }
                    c == '"' -> { inQuotes = false; i++; continue }
                    else -> {
                        if (c == '\n') line++
                        field.append(c); i++; continue
                    }
                }
            } else {
                when (c) {
                    '"' -> {
                        // A quote is only an opening quote at the start of a field; otherwise keep it literally.
                        if (field.isEmpty() && !fieldHadQuotes) { inQuotes = true; fieldHadQuotes = true } else field.append(c)
                        i++
                    }
                    ',' -> { endField(); i++ }
                    '\r' -> {
                        i++
                        if (i < n && text[i] == '\n') i++
                        line++
                        endRecord()
                    }
                    '\n' -> { i++; line++; endRecord() }
                    else -> { field.append(c); i++ }
                }
            }
        }
        if (field.isNotEmpty() || fields.isNotEmpty() || fieldHadQuotes) endRecord()
        return records
    }
}
