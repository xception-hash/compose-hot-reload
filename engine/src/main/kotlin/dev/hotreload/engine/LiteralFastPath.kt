package dev.hotreload.engine

import dev.hotreload.protocol.LiteralType

/** A literal edit ready to push: the [key], its owning [helperClass], [type], and new boxed [value]. */
data class LiteralPush(val key: String, val helperClass: String, val type: Int, val value: Any)

/**
 * Detects the "only a literal constant changed" case so an edit can skip Gradle+d8+redefine
 * and update the value in place through Compose live literals (T24).
 *
 * The classifier diffs the edited text against the exact baseline the [LiteralTable] was
 * extracted from (the engine refreshes both together after every compile), so table offsets
 * line up with the baseline with no cumulative-shift bookkeeping. A change qualifies only
 * when the single contiguous edit region falls entirely inside ONE live-literal token whose
 * start offset is in the table, and the token still lexes as a single literal of the SAME
 * type after the edit. Everything else — multiple hunks, template strings, `const val`,
 * type changes, edits touching surrounding code — returns null and takes the normal path.
 */
object LiteralFastPath {

    /**
     * @param baseline source text at the last compile (the [entries] are offsets into it)
     * @param updated the just-saved source text
     * @param entries live literals for this file, from [LiteralTable.entriesFor]
     * @return the value to push, or null if this isn't a clean single-literal edit
     */
    fun detect(baseline: String, updated: String, entries: List<LiteralEntry>): LiteralPush? {
        if (baseline == updated || entries.isEmpty()) return null

        // Common prefix/suffix decomposition -> the one contiguous changed span. This is
        // always a single hunk; a literal-token check below rejects spans that aren't one
        // literal (e.g. two separate edits, which would engulf unchanged code between them).
        val minLen = minOf(baseline.length, updated.length)
        var p = 0
        while (p < minLen && baseline[p] == updated[p]) p++
        var s = 0
        while (s < minLen - p && baseline[baseline.length - 1 - s] == updated[updated.length - 1 - s]) s++
        val bChangeEnd = baseline.length - s
        val nChangeEnd = updated.length - s

        for (e in entries) {
            // The literal must start at or before the first changed char...
            if (e.offset > p) continue
            val bLitEnd = lexLiteral(baseline, e.offset, e.type)
            // ...and its token must fully contain the changed region in the baseline.
            if (bLitEnd < bChangeEnd) continue

            // The same literal in the updated text spans [offset, nLitEnd); require it to
            // still lex as exactly one literal of the same type ending precisely there
            // (guards against the edit spilling into adjacent code or adding a template).
            val nLitEnd = nChangeEnd + (bLitEnd - bChangeEnd)
            if (lexLiteral(updated, e.offset, e.type) != nLitEnd) continue

            val value = parseValue(updated, e.offset, nLitEnd, e.type) ?: continue
            return LiteralPush(e.key, e.helperClass, e.type, value)
        }
        return null
    }

    /**
     * If a single literal of [type] starts at [start] in [text], return its end index
     * (exclusive); otherwise -1. Rejects raw/triple-quoted strings and template strings.
     */
    private fun lexLiteral(text: String, start: Int, type: Int): Int {
        if (start < 0 || start >= text.length) return -1
        return when (type) {
            LiteralType.STRING -> lexString(text, start)
            LiteralType.INT, LiteralType.LONG, LiteralType.FLOAT, LiteralType.DOUBLE -> lexNumber(text, start)
            LiteralType.BOOLEAN -> lexKeyword(text, start, "true") ?: lexKeyword(text, start, "false") ?: -1
            LiteralType.CHAR -> lexChar(text, start)
            else -> -1
        }
    }

    private fun lexString(text: String, start: Int): Int {
        if (text[start] != '"') return -1
        if (text.startsWith("\"\"\"", start)) return -1 // raw string: out of scope
        var i = start + 1
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2 // skip the escaped char
                '"' -> return i + 1
                '\n' -> return -1 // unterminated
                '$' -> {
                    // A live-literal string is never a template; an edit that introduces
                    // one ($var or ${...}) must fall back to the normal path.
                    val next = text.getOrNull(i + 1)
                    if (next == '{' || (next != null && (next.isLetter() || next == '_'))) return -1
                    i++
                }
                else -> i++
            }
        }
        return -1
    }

    private fun lexChar(text: String, start: Int): Int {
        if (text[start] != '\'') return -1
        var i = start + 1
        if (i >= text.length) return -1
        i += if (text[i] == '\\') 2 else 1
        return if (i < text.length && text[i] == '\'') i + 1 else -1
    }

    private fun lexKeyword(text: String, start: Int, word: String): Int? {
        if (!text.startsWith(word, start)) return null
        val after = text.getOrNull(start + word.length)
        // Not part of a longer identifier (e.g. `trueish`).
        if (after != null && (after.isLetterOrDigit() || after == '_')) return null
        return start + word.length
    }

    /** Lex a Kotlin numeric literal (decimal/hex/binary, underscores, exponent, f/F/L suffix). */
    private fun lexNumber(text: String, start: Int): Int {
        var i = start
        if (i >= text.length || (!text[i].isDigit())) return -1
        // hex / binary prefix
        if (text[i] == '0' && i + 1 < text.length && (text[i + 1] == 'x' || text[i + 1] == 'X' || text[i + 1] == 'b' || text[i + 1] == 'B')) {
            i += 2
            while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        } else {
            while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
            if (i < text.length && text[i] == '.' && i + 1 < text.length && text[i + 1].isDigit()) {
                i++
                while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
            }
            if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                var j = i + 1
                if (j < text.length && (text[j] == '+' || text[j] == '-')) j++
                if (j < text.length && text[j].isDigit()) {
                    i = j
                    while (i < text.length && (text[i].isDigit() || text[i] == '_')) i++
                }
            }
            if (i < text.length && (text[i] == 'f' || text[i] == 'F' || text[i] == 'L')) i++
        }
        return if (i > start) i else -1
    }

    /** Parse the literal token `text[start until end]` of [type] into its boxed value, or null. */
    private fun parseValue(text: String, start: Int, end: Int, type: Int): Any? {
        val raw = text.substring(start, end)
        return try {
            when (type) {
                LiteralType.STRING -> unescapeString(raw.substring(1, raw.length - 1))
                LiteralType.BOOLEAN -> raw == "true"
                LiteralType.CHAR -> unescapeChar(raw.substring(1, raw.length - 1))
                LiteralType.INT -> parseIntLiteral(raw)
                LiteralType.LONG -> parseLongLiteral(raw)
                LiteralType.FLOAT -> raw.removeSuffix("f").removeSuffix("F").replace("_", "").toFloat()
                LiteralType.DOUBLE -> raw.replace("_", "").toDouble()
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun parseIntLiteral(raw: String): Int {
        val t = raw.replace("_", "")
        return when {
            t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toLong(16).toInt()
            t.startsWith("0b") || t.startsWith("0B") -> t.substring(2).toLong(2).toInt()
            else -> t.toInt()
        }
    }

    private fun parseLongLiteral(raw: String): Long {
        val t = raw.removeSuffix("L").removeSuffix("l").replace("_", "")
        return when {
            t.startsWith("0x") || t.startsWith("0X") -> t.substring(2).toULong(16).toLong()
            t.startsWith("0b") || t.startsWith("0B") -> t.substring(2).toULong(2).toLong()
            else -> t.toLong()
        }
    }

    private fun unescapeString(body: String): String {
        if ('\\' !in body) return body
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                i += appendEscape(sb, body, i + 1) + 1
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    private fun unescapeChar(body: String): Char {
        if (body.length == 1) return body[0]
        val sb = StringBuilder(1)
        appendEscape(sb, body, 1)
        return sb[0]
    }

    /** Append the escape at [body]`[i]` (after the backslash) and return chars consumed after `\`. */
    private fun appendEscape(sb: StringBuilder, body: String, i: Int): Int {
        return when (val e = body[i]) {
            'n' -> { sb.append('\n'); 1 }
            't' -> { sb.append('\t'); 1 }
            'r' -> { sb.append('\r'); 1 }
            'b' -> { sb.append('\b'); 1 }
            '\\' -> { sb.append('\\'); 1 }
            '"' -> { sb.append('"'); 1 }
            '\'' -> { sb.append('\''); 1 }
            '$' -> { sb.append('$'); 1 }
            'u' -> { sb.append(body.substring(i + 1, i + 5).toInt(16).toChar()); 5 }
            else -> { sb.append(e); 1 }
        }
    }
}
