package com.crabcode.ide.ai.tools

import com.crabcode.ide.ai.providers.ToolSchema
import java.io.File

/**
 * Agent operating modes — same contract as the desktop Agent/Plan/Ask modes.
 */
enum class AgentMode { AGENT, PLAN, ASK }

/** Pending confirmation shown as a separate chat item. */
data class ApprovalRequest(
    val id: String,
    val kind: Kind,
    val title: String,
    val detail: String,
    val payload: Map<String, String>
) {
    enum class Kind { RUN_COMMAND, DELETE_PATH, MOVE_PATH, GENERIC }
}

interface ApprovalGate {
    /**
     * Suspends until the user answers. Returns true = allow.
     * Must be safe to call from any dispatcher; UI implements the dialog.
     */
    suspend fun request(approval: ApprovalRequest): Boolean
}

object NoApproval : ApprovalGate {
    override suspend fun request(approval: ApprovalRequest): Boolean = true
}

class ToolExecutionException(message: String) : Exception(message)

/**
 * The mobile tool registry — a compatible subset of the desktop TOOL_DEFS
 * (agentTools.ts). File tools operate inside the project sandbox; command tools
 * run REAL processes through PRoot; plan/memory/skill tools keep the original
 * formats (.crab/MEMORY.md, .crab/skills/<n>/SKILL.md).
 */
class ToolRegistry(
    private val projectRoot: File,
    private val userHome: File,
    private val commandRunner: suspend (String) -> ProotCommandResult,
    private val approvalGate: ApprovalGate,
    private val mode: AgentMode,
    private val onFileChange: suspend (path: String, kind: FileChangeKind) -> Unit = { _, _ -> },
    private val onPlanUpdate: suspend (json: String) -> Unit = {},
    private val onCommandOutput: (output: String) -> Unit = {},
    private val mcpTools: () -> List<McpToolRef> = { emptyList() },
    private val mcpCall: suspend (server: String, tool: String, argsJson: String) -> String = { _, _, _ -> "MCP недоступен" }
) {
    enum class FileChangeKind { CREATED, MODIFIED, DELETED, MOVED }

    data class ProotCommandResult(val exitCode: Int, val output: String)

    data class McpToolRef(val server: String, val tool: String, val description: String)

    // ------------------------------------------------------------- schemas

    fun schemas(): List<ToolSchema> {
        val base = mutableListOf(
            ToolSchema(
                "read_file",
                "Прочитать текстовый файл проекта. Возвращает содержимое с номерами строк.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Путь относительно корня проекта"},"limit":{"type":"integer","description":"Максимальное число строк"}},"required":["path"]}"""
            ),
            ToolSchema(
                "write_file",
                "Создать файл или полностью перезаписать его содержимое.",
                """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}"""
            ),
            ToolSchema(
                "edit_file",
                "Заменить фрагмент текста в файле. Все вхождения old_text должны совпадать точно.",
                """{"type":"object","properties":{"path":{"type":"string"},"old_text":{"type":"string"},"new_text":{"type":"string"}},"required":["path","old_text","new_text"]}"""
            ),
            ToolSchema(
                "list_dir",
                "Показать файлы и каталоги в директории проекта.",
                """{"type":"object","properties":{"path":{"type":"string","description":"Каталог относительно корня проекта, по умолчанию ."}},"required":[]}"""
            ),
            ToolSchema(
                "search",
                "Поиск по содержимому файлов проекта (regex).",
                """{"type":"object","properties":{"pattern":{"type":"string"},"path":{"type":"string"}},"required":["pattern"]}"""
            ),
            ToolSchema(
                "create_dir",
                "Создать каталог внутри проекта.",
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            ),
            ToolSchema(
                "delete_path",
                "Удалить файл или каталог. Требует подтверждения пользователя.",
                """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}"""
            ),
            ToolSchema(
                "move_path",
                "Переместить или переименовать файл/каталог. Требует подтверждения.",
                """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}"""
            ),
            ToolSchema(
                "copy_path",
                "Копировать файл или каталог.",
                """{"type":"object","properties":{"from":{"type":"string"},"to":{"type":"string"}},"required":["from","to"]}"""
            ),
            ToolSchema(
                "run_command",
                "Немедленно выполнить shell-команду в Linux-окружении проекта (PRoot). Используй ТОЛЬКО когда пользователь явно просит выполнить команду. Иначе используй propose_command.",
                """{"type":"object","properties":{"command":{"type":"string"}},"required":["command"]}"""
            ),
            ToolSchema(
                "propose_command",
                "Предложить команду на подтверждение пользователю и выполнить после одобрения. Используй для сборок, тестов, установки пакетов, git.",
                """{"type":"object","properties":{"command":{"type":"string"},"reason":{"type":"string"}},"required":["command","reason"]}"""
            ),
            ToolSchema(
                "update_plan",
                "Обновить видимый план выполнения задачи (шаги со статусами pending/in_progress/done).",
                """{"type":"object","properties":{"steps":{"type":"array","items":{"type":"object","properties":{"title":{"type":"string"},"status":{"type":"string","enum":["pending","in_progress","done"]}}}}},"required":["steps"]}"""
            ),
            ToolSchema(
                "read_memory",
                "Прочитать постоянную память проекта (.crab/MEMORY.md).",
                """{"type":"object","properties":{},"required":[]}"""
            ),
            ToolSchema(
                "write_memory",
                "Добавить заметку в память проекта (.crab/MEMORY.md).",
                """{"type":"object","properties":{"note":{"type":"string"}},"required":["note"]}"""
            ),
            ToolSchema(
                "list_skills",
                "Список доступных скиллов проекта (.crab/skills).",
                """{"type":"object","properties":{},"required":[]}"""
            ),
            ToolSchema(
                "load_skill",
                "Загрузить инструкции скилла в контекст.",
                """{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}"""
            ),
            ToolSchema(
                "fetch_url",
                "Скачать документ по HTTP(S) и вернуть текст (без тегов скриптов).",
                """{"type":"object","properties":{"url":{"type":"string"}},"required":["url"]}"""
            )
        )
        mcpTools().forEach { m ->
            base += ToolSchema("mcp__${m.server}__${m.tool}", "[MCP/${m.server}] ${m.description}")
        }
        return base
    }

    // ------------------------------------------------------------- execution

    suspend fun execute(name: String, argsJson: String): String {
        if (name.startsWith("mcp__")) {
            val parts = name.split("__")
            if (parts.size == 3) return mcpCall(parts[1], parts[2], argsJson)
        }
        val args: Map<String, String> = runCatching<Map<String, String>> {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(argsJson) as? kotlinx.serialization.json.JsonObject
            obj?.mapValues { (_, v) ->
                (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString()
            } ?: emptyMap()
        }.getOrDefault(emptyMap())

        return when (name) {
            "read_file" -> readFile(args["path"] ?: ".", args["limit"]?.toIntOrNull())
            "write_file" -> writeFile(args["path"] ?: error("path required"), args["content"] ?: "")
            "edit_file" -> editFile(args["path"] ?: error("path required"), args["old_text"] ?: error("old_text required"), args["new_text"] ?: "")
            "list_dir" -> listDir(args["path"] ?: ".")
            "search" -> search(args["pattern"] ?: error("pattern required"), args["path"] ?: ".")
            "create_dir" -> createDir(args["path"] ?: error("path required"))
            "delete_path" -> deletePath(args["path"] ?: error("path required"))
            "move_path" -> movePath(args["from"] ?: error("from required"), args["to"] ?: error("to required"))
            "copy_path" -> copyPath(args["from"] ?: error("from required"), args["to"] ?: error("to required"))
            "run_command" -> runCommand(args["command"] ?: error("command required"))
            "propose_command" -> proposeCommand(args["command"] ?: error("command required"), args["reason"] ?: "")
            "update_plan" -> updatePlan(argsJson)
            "read_memory" -> readMemory()
            "write_memory" -> writeMemory(args["note"] ?: error("note required"))
            "list_skills" -> listSkills()
            "load_skill" -> loadSkill(args["name"] ?: error("name required"))
            "fetch_url" -> fetchUrl(args["url"] ?: error("url required"))
            else -> "Неизвестный инструмент: $name"
        }
    }

    // ------------------------------------------------------------- sandbox

    /** Resolve a project-relative path; refuses escapes outside the sandbox roots. */
    fun sandboxResolve(rel: String): File {
        var clean = rel.trim()
        // The proot session binds the project at /workspace — accept that prefix
        // so paths copied from terminal output keep working.
        if (clean == "/workspace") clean = "."
        else if (clean.startsWith("/workspace/")) clean = clean.removePrefix("/workspace/")
        else if (clean.startsWith("/")) {
            // Absolute paths outside /workspace are never silently rewritten to
            // project paths — reject them explicitly.
            throw ToolExecutionException("Путь вне песочницы проекта (используйте относительный путь): $rel")
        }
        clean = clean.removePrefix("./")
        if (clean.isEmpty()) clean = "."
        if (clean.startsWith("~")) {
            throw ToolExecutionException("Домашний каталог вне проекта недоступен: $rel")
        }
        val f = File(projectRoot, clean).canonicalFile
        if (f == projectRoot.canonicalFile || f.startsWith(projectRoot.canonicalFile.toString() + File.separator)) return f
        // .crab and project files live under the project; user home is allowed
        // for cases the agent copies files into its Linux home.
        val home = File(userHome, clean).canonicalFile
        if (home == userHome.canonicalFile || home.startsWith(userHome.canonicalFile.toString() + File.separator)) return home
        throw ToolExecutionException("Путь вне песочницы проекта: $rel")
    }

    private fun readFile(path: String, limit: Int?): String {
        val f = sandboxResolve(path)
        if (!f.exists()) throw ToolExecutionException("Файл не найден: $path")
        if (f.isDirectory) return listDir(path)
        val text = f.readText()
        val lines = text.lines()
        val shown = limit?.let { lines.take(it) } ?: lines
        if (shown.isEmpty()) return "(пустой файл)"
        return shown.mapIndexed { i, l -> "${i + 1}| $l" }.joinToString("\n") +
            if (limit != null && lines.size > limit) "\n... (${lines.size - limit} строк скрыто)" else ""
    }

    private suspend fun writeFile(path: String, content: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: запись запрещена")
        if (mode == AgentMode.ASK) {
            val ok = approvalGate.request(ApprovalRequest(java.util.UUID.randomUUID().toString(),
                ApprovalRequest.Kind.GENERIC, "Запись файла", path, mapOf("path" to path)))
            if (!ok) return "Пользователь отклонил запись $path"
        }
        val f = sandboxResolve(path)
        if (f.exists() && f.isDirectory) throw ToolExecutionException("Это каталог: $path")
        f.parentFile?.mkdirs()
        f.writeText(content)
        onFileChange(path, FileChangeKind.MODIFIED)
        return "Записано ${content.length} символов в $path"
    }

    private suspend fun editFile(path: String, oldText: String, newText: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: изменение запрещено")
        if (mode == AgentMode.ASK) {
            val ok = approvalGate.request(ApprovalRequest(java.util.UUID.randomUUID().toString(),
                ApprovalRequest.Kind.GENERIC, "Изменение файла", path, mapOf("path" to path)))
            if (!ok) return "Пользователь отклонил изменение $path"
        }
        val f = sandboxResolve(path)
        if (!f.isFile) throw ToolExecutionException("Файл не найден: $path")
        val text = f.readText()
        val idx = text.indexOf(oldText)
        when {
            idx < 0 -> throw ToolExecutionException("Фрагмент не найден в $path")
            text.indexOf(oldText, idx + 1) >= 0 -> throw ToolExecutionException("Фрагмент встречается более одного раза — уточните контекст")
        }
        f.writeText(text.replaceRange(idx, idx + oldText.length, newText))
        onFileChange(path, FileChangeKind.MODIFIED)
        return "Файл $path изменён"
    }

    private fun listDir(path: String): String {
        val f = sandboxResolve(path)
        if (!f.exists()) throw ToolExecutionException("Каталог не найден: $path")
        if (f.isFile) return "(файл, ${f.length()} байт)"
        val entries = f.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: return "(пусто)"
        if (entries.isEmpty()) return "(пусто)"
        return entries.joinToString("\n") { e ->
            (if (e.isDirectory) "[DIR]  " else "       ") + e.name + if (e.isFile) "  (${e.length()} B)" else ""
        }
    }

    private fun search(pattern: String, path: String): String {
        val root = sandboxResolve(path)
        val regex = runCatching { Regex(pattern) }.getOrElse { Regex(Regex.escape(pattern)) }
        val results = mutableListOf<String>()
        var scanned = 0
        fun walk(dir: File, depth: Int) {
            if (depth > 6 || results.size >= 60) return
            dir.listFiles()?.forEach { f ->
                if (f.name.startsWith(".git/") || f.name == ".git") return@forEach
                if (f.isDirectory) walk(f, depth + 1)
                else if (f.length() < 1_000_000 && f.extension !in BINARY_EXT) {
                    scanned++
                    runCatching {
                        f.readText().lineSequence().forEachIndexed { i, line ->
                            if (regex.containsMatchIn(line) && results.size < 60) {
                                results += "${f.relativeTo(projectRoot)}:$i: ${line.trim().take(200)}"
                            }
                        }
                    }
                }
            }
        }
        walk(root, 0)
        if (results.isEmpty()) return "Совпадений нет (просмотрено файлов: $scanned)"
        return results.joinToString("\n") + "\n${results.size} совпадений"
    }

    private fun createDir(path: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: создание каталогов запрещено")
        val f = sandboxResolve(path)
        f.mkdirs()
        return "Каталог создан: $path"
    }

    private suspend fun deletePath(path: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: удаление запрещено")
        val ok = approvalGate.request(ApprovalRequest(java.util.UUID.randomUUID().toString(),
            ApprovalRequest.Kind.DELETE_PATH, "Удаление", path, mapOf("path" to path)))
        if (!ok) return "Пользователь отклонил удаление $path"
        val f = sandboxResolve(path)
        if (!f.exists()) return "Путь не существует: $path"
        val n = if (f.isDirectory) f.walkBottomUp().count() else 1
        f.deleteRecursively()
        onFileChange(path, FileChangeKind.DELETED)
        return "Удалено: $path ($n объектов)"
    }

    private suspend fun movePath(from: String, to: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: перемещение запрещено")
        val ok = approvalGate.request(ApprovalRequest(java.util.UUID.randomUUID().toString(),
            ApprovalRequest.Kind.MOVE_PATH, "Перемещение", "$from → $to", mapOf("from" to from, "to" to to)))
        if (!ok) return "Пользователь отклонил перемещение"
        val src = sandboxResolve(from); val dst = sandboxResolve(to)
        if (!src.exists()) return "Источник не найден: $from"
        dst.parentFile?.mkdirs()
        if (src.renameTo(dst)) {
            onFileChange(to, FileChangeKind.MOVED)
            return "Перемещено: $from → $to"
        }
        src.copyRecursively(dst, overwrite = true)
        src.deleteRecursively()
        onFileChange(to, FileChangeKind.MOVED)
        return "Перемещено (копированием): $from → $to"
    }

    private suspend fun copyPath(from: String, to: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: копирование запрещено")
        val src = sandboxResolve(from); val dst = sandboxResolve(to)
        if (!src.exists()) return "Источник не найден: $from"
        dst.parentFile?.mkdirs()
        if (src.isDirectory) src.copyRecursively(dst, overwrite = true) else src.copyTo(dst, overwrite = true)
        onFileChange(to, FileChangeKind.CREATED)
        return "Скопировано: $from → $to"
    }

    private suspend fun runCommand(command: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: команды запрещены")
        return execCommand(command, needsApproval = false)
    }

    private suspend fun proposeCommand(command: String, reason: String): String {
        val ok = approvalGate.request(ApprovalRequest(java.util.UUID.randomUUID().toString(),
            ApprovalRequest.Kind.RUN_COMMAND, "Команда: $command", reason, mapOf("command" to command)))
        if (!ok) return "Пользователь отклонил команду: $command"
        return execCommand(command, needsApproval = false)
    }

    private suspend fun execCommand(command: String, needsApproval: Boolean): String {
        val r = commandRunner(command)
        val output = r.output.trim()
        onCommandOutput("\$ $command\r\n$output\r\n")
        return buildString {
            append("exit code: ").append(r.exitCode).append('\n')
            append(if (output.isEmpty()) "(нет вывода)" else output.take(20_000))
        }
    }

    private suspend fun updatePlan(argsJson: String): String {
        onPlanUpdate(argsJson)
        return "План обновлён"
    }

    private fun readMemory(): String {
        val f = File(projectRoot, ".crab/MEMORY.md")
        return if (f.exists()) f.readText() else "(память пуста)"
    }

    private fun writeMemory(note: String): String {
        if (mode == AgentMode.PLAN) throw ToolExecutionException("Режим Plan: память недоступна для записи")
        val f = File(projectRoot, ".crab/MEMORY.md")
        f.parentFile?.mkdirs()
        f.appendText("\n- $note\n")
        return "Заметка сохранена в .crab/MEMORY.md"
    }

    private fun listSkills(): String {
        val dir = File(projectRoot, ".crab/skills")
        val skills = dir.listFiles()?.filter { it.isDirectory } ?: return "(скиллы не установлены)"
        if (skills.isEmpty()) return "(скиллы не установлены)"
        return skills.joinToString("\n") { s ->
            val fm = readFrontmatter(File(s, "SKILL.md"))
            "${s.name} — ${fm.second}"
        }
    }

    private fun loadSkill(name: String): String {
        val f = File(File(projectRoot, ".crab/skills"), "$name/SKILL.md")
        if (!f.exists()) return "Скилл не найден: $name"
        return f.readText()
    }

    private fun fetchUrl(url: String): String {
        if (!url.startsWith("https://")) throw ToolExecutionException("Только https:// ссылки")
        val client = okhttp3.OkHttpClient.Builder()
            .callTimeout(java.time.Duration.ofSeconds(25))
            .followRedirects(true)
            .build()
        return runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use "HTTP ${resp.code}"
                val mime = resp.header("Content-Type") ?: ""
                val body = resp.body!!.string()
                when {
                    mime.contains("html", true) -> body
                        .replace(Regex("(?is)<script.*?</script>"), "")
                        .replace(Regex("(?is)<style.*?</style>"), "")
                        .replace(Regex("(?s)<[^>]+>"), " ")
                        .replace(Regex("\\s{2,}"), " ")
                        .trim().take(30_000)
                    else -> body.take(30_000)
                }
            }
        }.getOrElse { "Ошибка загрузки: ${it.message}" }
    }

    companion object {
        private val BINARY_EXT = setOf("png", "jpg", "jpeg", "gif", "webp", "ico", "pdf", "zip", "gz",
            "tar", "mp3", "mp4", "apk", "so", "bin", "exe", "class", "jar", "woff", "woff2", "ttf")

        fun readFrontmatter(file: File): Pair<String, String> {
            if (!file.exists()) return "" to ""
            val text = file.readText()
            if (!text.startsWith("---")) return "" to ""
            val end = text.indexOf("\n---", 3)
            if (end < 0) return "" to ""
            val fm = text.substring(3, end).trim()
            var name = ""; var description = ""
            fm.lineSequence().forEach { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    when (line.substring(0, idx).trim()) {
                        "name" -> name = line.substring(idx + 1).trim()
                        "description" -> description = line.substring(idx + 1).trim()
                    }
                }
            }
            return name to description
        }
    }
}
