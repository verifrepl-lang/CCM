package com.crabcode.ide.editor

/**
 * Lightweight regex syntax highlighter for the mobile editor — a pragmatic
 * substitute for Monaco (which cannot run on Android). Covers keywords,
 * strings, comments, numbers, annotations and types for common languages.
 */
object SyntaxHighlighter {

    enum class TokenType { PLAIN, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION, OPERATOR }

    data class Token(val text: String, val type: TokenType)

    private data class Rule(val regex: Regex, val type: TokenType)

    private val LANG_KEYWORDS: Map<String, List<String>> = mapOf(
        "python" to listOf("def", "class", "import", "from", "return", "if", "elif", "else", "for", "while", "try", "except", "finally", "with", "as", "lambda", "yield", "async", "await", "pass", "break", "continue", "raise", "global", "nonlocal", "assert", "del", "in", "is", "not", "and", "or", "None", "True", "False", "self"),
        "javascript" to listOf("function", "class", "const", "let", "var", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "new", "delete", "typeof", "instanceof", "in", "of", "try", "catch", "finally", "throw", "async", "await", "yield", "import", "export", "from", "extends", "this", "super", "null", "undefined", "true", "false", "static", "get", "set"),
        "typescript" to listOf("function", "class", "const", "let", "var", "return", "if", "else", "for", "while", "switch", "case", "default", "new", "typeof", "instanceof", "in", "of", "try", "catch", "finally", "throw", "async", "await", "yield", "import", "export", "from", "extends", "implements", "interface", "type", "enum", "namespace", "declare", "abstract", "public", "private", "protected", "readonly", "as", "this", "super", "null", "undefined", "true", "false", "string", "number", "boolean", "any", "unknown", "never", "void"),
        "kotlin" to listOf("fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when", "if", "else", "for", "while", "do", "return", "break", "continue", "import", "package", "suspend", "override", "open", "abstract", "final", "private", "protected", "internal", "public", "companion", "init", "constructor", "by", "lazy", "lateinit", "is", "in", "as", "this", "super", "null", "true", "false", "try", "catch", "finally", "throw"),
        "rust" to listOf("fn", "let", "mut", "const", "struct", "enum", "impl", "trait", "mod", "pub", "use", "crate", "self", "Self", "super", "match", "if", "else", "for", "while", "loop", "break", "continue", "return", "where", "as", "dyn", "ref", "move", "async", "await", "unsafe", "type", "true", "false", "Some", "None", "Ok", "Err"),
        "go" to listOf("func", "var", "const", "type", "struct", "interface", "map", "chan", "go", "defer", "select", "switch", "case", "default", "if", "else", "for", "range", "return", "break", "continue", "package", "import", "nil", "true", "false", "error", "string", "int", "int64", "float64", "bool", "byte", "rune"),
        "java" to listOf("public", "private", "protected", "class", "interface", "extends", "implements", "static", "final", "abstract", "void", "int", "long", "double", "float", "boolean", "char", "byte", "short", "new", "return", "if", "else", "for", "while", "do", "switch", "case", "default", "break", "continue", "try", "catch", "finally", "throw", "throws", "import", "package", "this", "super", "null", "true", "false", "instanceof", "enum", "record"),
        "shell" to listOf("if", "then", "else", "elif", "fi", "for", "in", "do", "done", "while", "until", "case", "esac", "function", "return", "exit", "export", "local", "readonly", "set", "unset", "echo", "cd", "source", "alias", "shift"),
        "css" to listOf("important"),
        "html" to emptyList(),
        "markdown" to emptyList(),
        "json" to listOf("true", "false", "null"),
        "yaml" to listOf("true", "false", "null", "yes", "no", "on", "off")
    )

    private val COMMENT_STYLE: Map<String, String> = mapOf(
        "python" to "#", "shell" to "#", "yaml" to "#", "ruby" to "#", "toml" to "#",
        "javascript" to "//", "typescript" to "//", "kotlin" to "//", "rust" to "//",
        "go" to "//", "java" to "//", "css" to "/*", "c" to "//", "cpp" to "//", "swift" to "//", "dart" to "//"
    )

    fun languageOf(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "py", "pyw" -> "python"
        "js", "mjs", "cjs", "jsx" -> "javascript"
        "ts", "tsx", "mts" -> "typescript"
        "kt", "kts" -> "kotlin"
        "rs" -> "rust"
        "go" -> "go"
        "java" -> "java"
        "sh", "bash", "zsh" -> "shell"
        "css", "scss", "less" -> "css"
        "html", "htm", "xml", "svg" -> "html"
        "json" -> "json"
        "yaml", "yml" -> "yaml"
        "c", "h" -> "c"
        "cpp", "hpp", "cc" -> "cpp"
        "swift" -> "swift"
        "dart" -> "dart"
        else -> "text"
    }

    fun tokenize(line: String, language: String): List<Token> {
        if (language == "text") return listOf(Token(line, TokenType.PLAIN))
        val rules = buildRules(language)
        val keywords = LANG_KEYWORDS[language] ?: emptyList()
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < line.length) {
            var matched = false
            for ((regex, type) in rules) {
                val m = regex.matchAt(line, i)
                if (m != null) {
                    val wordType = when {
                        type != TokenType.PLAIN -> type
                        m.value in keywords -> TokenType.KEYWORD
                        m.value.firstOrNull()?.isUpperCase() == true -> TokenType.TYPE
                        else -> TokenType.PLAIN
                    }
                    tokens += Token(m.value, wordType)
                    i += m.value.length
                    matched = true
                    break
                }
            }
            if (!matched) {
                val start = i
                while (i < line.length && !line[i].isWhitespace() && !isPunct(line[i])) i++
                if (i == start) i++
                val word = line.substring(start, i)
                val type = when {
                    word in keywords -> TokenType.KEYWORD
                    word.firstOrNull()?.isUpperCase() == true -> TokenType.TYPE
                    else -> TokenType.PLAIN
                }
                tokens += Token(word, type)
            }
        }
        return mergePlain(tokens)
    }

    private fun isPunct(c: Char): Boolean = !c.isLetterOrDigit() && c != '_' && c != '$'

    private fun buildRules(language: String): List<Rule> {
        val comment = COMMENT_STYLE[language]
        val rules = mutableListOf<Rule>()
        if (comment == "/*") {
            rules += Rule(Regex("/\\*.*?(\\*/|$)"), TokenType.COMMENT)
        } else if (comment != null) {
            rules += Rule(Regex("${Regex.escape(comment)}.*"), TokenType.COMMENT)
        }
        rules += Rule(Regex("\"\"\"[\\s\\S]*?\"\"\"|^'''[\\s\\S]*?'''"), TokenType.STRING) // python docstrings
        rules += Rule(Regex("(\"([^\"\\\\]|\\\\.)*\"|'([^'\\\\]|\\\\.)*'|`([^`\\\\]|\\\\.)*`)"), TokenType.STRING)
        rules += Rule(Regex("\\d+(\\.\\d+)?([eE][+-]?\\d+)?[fFlLuU]*"), TokenType.NUMBER)
        rules += Rule(Regex("0x[0-9a-fA-F]+"), TokenType.NUMBER)
        rules += Rule(Regex("@[_a-zA-Z][_a-zA-Z0-9]*"), TokenType.ANNOTATION)
        rules += Rule(Regex("[_a-zA-Z][_a-zA-Z0-9$]*\\s*(?=\\()"), TokenType.FUNCTION)
        rules += Rule(Regex("[+\\-*/%=<>!&|^~?:]+"), TokenType.OPERATOR)
        rules += Rule(Regex("[{}()\\[\\];,.]"), TokenType.PLAIN)
        rules += Rule(Regex("[ \\t]+"), TokenType.PLAIN)
        rules += Rule(Regex("[_\$a-zA-Z][_\$a-zA-Z0-9]*"), TokenType.PLAIN)
        rules += Rule(Regex("."), TokenType.PLAIN)
        return rules
    }

    private fun mergePlain(tokens: List<Token>): List<Token> {
        val out = mutableListOf<Token>()
        for (t in tokens) {
            val last = out.lastOrNull()
            if (last != null && last.type == TokenType.PLAIN && t.type == TokenType.PLAIN) {
                out[out.size - 1] = Token(last.text + t.text, TokenType.PLAIN)
            } else out += t
        }
        return out
    }
}
