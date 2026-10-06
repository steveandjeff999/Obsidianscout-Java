package com.obsidianscout.utils

object CSVHelper {
    fun toCSV(headers: List<String>, rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.append(headers.joinToString(",") { escapeCSV(it) }).append("\r\n")
        for (row in rows) {
            sb.append(row.joinToString(",") { escapeCSV(it) }).append("\r\n")
        }
        return sb.toString()
    }

    // Spreadsheet apps run cells starting with these characters as formulas.
    private val FORMULA_PREFIXES = charArrayOf('=', '+', '-', '@', '\t', '\r')
    private val PLAIN_NUMBER = Regex("^[+-]?\\d+(\\.\\d+)?([eE][+-]?\\d+)?$")

    /** Prefixes formula-like text with an apostrophe so spreadsheets show it as text. Numbers are left alone. */
    fun neutralizeFormula(value: String): String =
        if (value.isNotEmpty() && value[0] in FORMULA_PREFIXES && !PLAIN_NUMBER.matches(value)) "'$value" else value

    /** Reverses [neutralizeFormula] when reading a CSV this app exported. */
    fun restoreFormula(value: String): String =
        if (value.length > 1 && value[0] == '\'' && value[1] in FORMULA_PREFIXES && !PLAIN_NUMBER.matches(value.substring(1))) value.substring(1) else value

    fun escapeCSV(value: String?): String {
        if (value == null) return ""
        val escaped = neutralizeFormula(value).replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    fun parseCSV(csvContent: String): List<Map<String, String>> {
        if (csvContent.isBlank()) return emptyList()

        val parsedRows = mutableListOf<List<String>>()
        val curVal = StringBuilder()
        var inQuotes = false
        var i = 0
        var curRow = mutableListOf<String>()

        while (i < csvContent.length) {
            val c = csvContent[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csvContent.length && csvContent[i + 1] == '"') {
                        curVal.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                } else {
                    curVal.append(c)
                }
            } else {
                if (c == '"') {
                    inQuotes = true
                } else if (c == ',') {
                    curRow.add(curVal.toString())
                    curVal.setLength(0)
                } else if (c == '\n' || c == '\r') {
                    curRow.add(curVal.toString())
                    curVal.setLength(0)

                    if (curRow.any { it.isNotBlank() }) {
                        parsedRows.add(curRow)
                    }
                    curRow = mutableListOf()

                    if (c == '\r' && i + 1 < csvContent.length && csvContent[i + 1] == '\n') {
                        i++
                    }
                } else {
                    curVal.append(c)
                }
            }
            i++
        }

        if (curVal.isNotEmpty() || curRow.isNotEmpty()) {
            curRow.add(curVal.toString())
            if (curRow.any { it.isNotBlank() }) {
                parsedRows.add(curRow)
            }
        }

        if (parsedRows.isEmpty()) return emptyList()

        val headers = parsedRows[0].map { it.trim() }
        val results = mutableListOf<Map<String, String>>()

        for (rowIdx in 1 until parsedRows.size) {
            val row = parsedRows[rowIdx]
            if (row.size == headers.size) {
                results.add(headers.zip(row.map { restoreFormula(it) }).toMap())
            }
        }
        return results
    }
}
