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

    fun escapeCSV(value: String?): String {
        if (value == null) return ""
        val escaped = value.replace("\"", "\"\"")
        return if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {
            "\"$escaped\""
        } else {
            escaped
        }
    }

    fun parseCSV(csvContent: String): List<Map<String, String>> {
        val parsedRows = mutableListOf<List<String>>()
        var curVal = StringBuilder()
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
                    curVal = StringBuilder()
                } else if (c == '\n' || c == '\r') {
                    curRow.add(curVal.toString())
                    curVal = StringBuilder()
                    if (curRow.isNotEmpty() && (curRow.size > 1 || curRow[0].isNotEmpty())) {
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
            parsedRows.add(curRow)
        }
        
        if (parsedRows.isEmpty()) return emptyList()
        
        val headers = parsedRows[0].map { it.trim() }
        val results = mutableListOf<Map<String, String>>()
        for (rowIdx in 1 until parsedRows.size) {
            val row = parsedRows[rowIdx]
            if (row.size == headers.size) {
                results.add(headers.zip(row).toMap())
            }
        }
        return results
    }
}
