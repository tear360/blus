package fr.tear36.blus.data

import java.io.Reader

/**
 * Minimal streaming GTFS CSV reader.
 *
 * Deliberately free of any Android dependency so it can be exercised by unit tests:
 * the parser has to survive 4.7M rows of quoted, comma-laden French stop names.
 */
object GtfsCsv {

    const val DEFAULT_MAX_FIELDS = 32

    /**
     * Reads one record into [fields] and returns the column count, or -1 at end of input.
     *
     * [fields] is padded to its full length with "" so callers can index column
     * positions safely even on short rows.
     */
    fun readRecord(reader: Reader, line: StringBuilder, fields: Array<String>): Int {
        if (!readLogicalLine(reader, line)) return -1
        return splitInto(line, fields)
    }

    /** Splits [line] into [fields] (padded). Returns the real column count. */
    fun splitInto(line: StringBuilder, fields: Array<String>): Int {
        val max = fields.size
        val len = line.length
        var count = 0
        var i = 0
        var afterSeparator = false
        val unquoted = StringBuilder(64)

        while (count < max) {
            if (i >= len) {
                // A line ending with a separator has one final empty column.
                if (afterSeparator) fields[count++] = ""
                break
            }
            if (line[i] == '"') {
                i++
                unquoted.setLength(0)
                while (i < len) {
                    val c = line[i]
                    if (c == '"') {
                        if (i + 1 < len && line[i + 1] == '"') {
                            unquoted.append('"'); i += 2
                        } else {
                            i++; break
                        }
                    } else {
                        unquoted.append(c); i++
                    }
                }
                while (i < len && line[i] != ',') i++
                fields[count++] = unquoted.toString()
            } else {
                val start = i
                while (i < len && line[i] != ',') i++
                fields[count++] = line.substring(start, i)
            }
            afterSeparator = i < len
            if (afterSeparator) i++
        }
        val realCount = count
        while (count < max) fields[count++] = ""
        return realCount
    }

    /**
     * Reads one logical record, honouring quoted fields that may contain newlines.
     * Returns false at end of input.
     */
    fun readLogicalLine(reader: Reader, out: StringBuilder): Boolean {
        out.setLength(0)
        var c = reader.read()
        if (c == -1) return false
        var inQuotes = false
        while (c != -1) {
            val ch = c.toChar()
            if (ch == '"') inQuotes = !inQuotes
            if (ch == '\n' && !inQuotes) {
                trimTrailingCr(out)
                return true
            }
            out.append(ch)
            c = reader.read()
        }
        trimTrailingCr(out)
        return true
    }

    private fun trimTrailingCr(sb: StringBuilder) {
        if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
    }

    /** Splits a single line into its fields (used for `agency.txt` style parsing). */
    fun splitLine(line: String): List<String> {
        val fields = arrayOfNulls<String>(24)
        val target = Array(fields.size) { "" }
        val n = splitInto(StringBuilder(line), target)
        return (0 until n).map { target[it] }
    }

    /** `04:53:00` (or `25:10:00` for night services) to seconds since midnight. */
    fun timeToSec(t: String): Int {
        if (t.length < 8) return -1
        val h = t.substring(0, 2).toIntOrNull() ?: return -1
        val m = t.substring(3, 5).toIntOrNull() ?: return -1
        val s = t.substring(6, 8).toIntOrNull() ?: return -1
        return h * 3600 + m * 60 + s
    }
}