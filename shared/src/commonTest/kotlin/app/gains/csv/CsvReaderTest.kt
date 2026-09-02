package app.gains.csv

import kotlin.test.Test
import kotlin.test.assertEquals

class CsvReaderTest {
    @Test
    fun parsesQuotedFieldsWithCommasQuotesAndNewlines() {
        val text = "a,b,c\n1,\"x, y\",\"he said \"\"hi\"\"\"\n2,\"multi\nline\",z\r\n3,,\n"
        val records = CsvReader.parse(text)
        assertEquals(4, records.size)
        assertEquals(listOf("1", "x, y", "he said \"hi\""), records[1].fields)
        assertEquals(listOf("2", "multi\nline", "z"), records[2].fields)
        assertEquals(listOf("3", "", ""), records[3].fields)
        assertEquals(2, records[1].lineNumber)
        assertEquals(3, records[2].lineNumber)
        assertEquals(5, records[3].lineNumber)
    }

    @Test
    fun skipsBlankLinesAndBom() {
        val text = "﻿a,b\n\n1,2\n\n"
        val records = CsvReader.parse(text)
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), records.map { it.fields })
    }

    @Test
    fun handlesFileWithoutTrailingNewline() {
        val records = CsvReader.parse("a,b\n1,2")
        assertEquals(2, records.size)
        assertEquals(listOf("1", "2"), records[1].fields)
    }
}
