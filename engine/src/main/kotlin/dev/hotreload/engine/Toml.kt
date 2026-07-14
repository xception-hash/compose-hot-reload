package dev.hotreload.engine

object Toml {
    fun parse(text: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        val lines = text.split(Regex("\r?\n"))
        for (i in lines.indices) {
            val lineNum = i + 1
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            if (trimmed.startsWith("#")) continue

            if (trimmed.startsWith("[")) {
                if (trimmed.endsWith("]")) {
                    throw IllegalArgumentException("line $lineNum: tables are not supported")
                }
            }

            val eqIdx = trimmed.indexOf('=')
            if (eqIdx == -1) {
                throw IllegalArgumentException("line $lineNum: invalid line format")
            }
            val keyPart = trimmed.substring(0, eqIdx).trim()
            if (!keyPart.matches(Regex("^[A-Za-z0-9_-]+$"))) {
                throw IllegalArgumentException("line $lineNum: invalid key '$keyPart'")
            }
            if (result.containsKey(keyPart)) {
                throw IllegalArgumentException("line $lineNum: duplicate key '$keyPart'")
            }

            val valuePart = trimmed.substring(eqIdx + 1).trim()
            val parsedValue: Any = when {
                valuePart.isEmpty() -> throw IllegalArgumentException("line $lineNum: value must not be empty")
                valuePart.startsWith("\"") -> {
                    val (parsedStr, nextIdx) = parseBasicString(valuePart, lineNum)
                    val rest = valuePart.substring(nextIdx).trim()
                    if (rest.isNotEmpty()) {
                        throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                    }
                    parsedStr
                }
                valuePart == "true" -> true
                valuePart == "false" -> false
                valuePart.startsWith("true") -> {
                    val rest = valuePart.substring(4).trim()
                    if (rest.isNotEmpty()) {
                        throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                    }
                    true
                }
                valuePart.startsWith("false") -> {
                    val rest = valuePart.substring(5).trim()
                    if (rest.isNotEmpty()) {
                        throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                    }
                    false
                }
                valuePart.first().isDigit() -> {
                    val digits = valuePart.takeWhile { it.isDigit() }
                    val rest = valuePart.substring(digits.length).trim()
                    if (rest.isNotEmpty()) {
                        throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                    }
                    digits.toLongOrNull() ?: throw IllegalArgumentException("line $lineNum: integer overflow")
                }
                valuePart.startsWith("[") -> {
                    var arrStr = valuePart.substring(1).trim()
                    val list = mutableListOf<String>()
                    if (arrStr.startsWith("]")) {
                        val rest = arrStr.substring(1).trim()
                        if (rest.isNotEmpty()) {
                            throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                        }
                        list
                    } else {
                        while (true) {
                            arrStr = arrStr.trim()
                            if (arrStr.isEmpty()) {
                                throw IllegalArgumentException("line $lineNum: arrays must be on one line")
                            }
                            if (arrStr.startsWith("]")) {
                                val rest = arrStr.substring(1).trim()
                                if (rest.isNotEmpty()) {
                                    throw IllegalArgumentException("line $lineNum: unexpected trailing content")
                                }
                                break
                            }
                            if (!arrStr.startsWith("\"")) {
                                throw IllegalArgumentException("line $lineNum: non-string elements")
                            }
                            val (parsedVal, nextIdx) = parseBasicString(arrStr, lineNum)
                            list.add(parsedVal)
                            arrStr = arrStr.substring(nextIdx).trim()
                            if (arrStr.startsWith(",")) {
                                arrStr = arrStr.substring(1).trim()
                            } else if (!arrStr.startsWith("]")) {
                                throw IllegalArgumentException("line $lineNum: expected comma or end of array")
                            }
                        }
                        list
                    }
                }
                else -> throw IllegalArgumentException("line $lineNum: invalid value format")
            }
            result[keyPart] = parsedValue
        }
        return result
    }

    fun writeString(s: String): String {
        val sb = StringBuilder()
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    private fun parseBasicString(str: String, lineNum: Int): Pair<String, Int> {
        if (str.isEmpty() || str[0] != '"') {
            throw IllegalArgumentException("line $lineNum: expected basic string")
        }
        val sb = StringBuilder()
        var i = 1
        while (i < str.length) {
            val c = str[i]
            if (c == '"') {
                return Pair(sb.toString(), i + 1)
            }
            if (c == '\\') {
                if (i + 1 >= str.length) {
                    throw IllegalArgumentException("line $lineNum: escape character at end of line")
                }
                val next = str[i + 1]
                when (next) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    else -> throw IllegalArgumentException("line $lineNum: invalid escape sequence '\\$next'")
                }
                i += 2
            } else {
                if (c.code < 0x20 || c.code == 0x7F) {
                    throw IllegalArgumentException("line $lineNum: raw control characters are not allowed in basic string")
                }
                sb.append(c)
                i++
            }
        }
        throw IllegalArgumentException("line $lineNum: unterminated basic string")
    }
}
