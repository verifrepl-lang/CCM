package com.crabcode.ide.ai.agent

import com.crabcode.ide.ai.tools.ToolRegistry
import java.io.File

/**
 * Builds the agent system prompt the same way desktop CrabCode does:
 * project context + steering files (.crab/CRAB.md и файлы .md из .crab/steering,
 * including files attached through the Android document picker) + memory +
 * skills catalog + mode policy + dialogue language.
 */
object SystemPrompt {

    fun build(
        projectRoot: File,
        mode: String,
        dialogueLanguage: String,
        attachedInstructionFiles: List<String> = emptyList()
    ): String = buildString {
        append(
            "Ты — CrabCode, ИИ-агент для разработки внутри мобильной IDE Crab-Code-IDE. " +
                "Ты работаешь в активном проекте и следуешь контролируемому циклу выполнения: " +
                "анализ задачи → выбор инструментов → чтение/поиск/правка/команды в пределах политики доступа → " +
                "потоковый отчёт о прогрессе → представление изменений для проверки.\n\n"
        )

        append("Текущий режим: ").append(
            when (mode) {
                "PLAN" -> "Plan — только инструменты чтения; верни план реализации, не изменяй файлы."
                "ASK" -> "Ask — инструменты доступны, но перед каждой изменяющей операцией запрашивай одобрение."
                else -> "Agent — инструменты доступны, изменения применяются в пределах активного проекта."
            }
        ).append("\n\n")

        append("Правила работы с файлами:\n")
        append("- Используй edit_file для правок (точные фрагменты), write_file для новых файлов.\n")
        append("- Для команд сборки/тестов/установки пакетов/git используй propose_command — пользователь подтвердит выполнение.\n")
        append("- run_command применяй только если пользователь явно просит выполнить команду.\n")
        append("- Не выходи за границы проекта; не удаляй файлы без подтверждения.\n")
        append("- Отвечай на языке диалога: $dialogueLanguage.\n\n")

        // steering: .crab/CRAB.md
        val crabMd = File(projectRoot, ".crab/CRAB.md")
        if (crabMd.isFile) {
            append("## Инструкции проекта (.crab/CRAB.md)\n").append(crabMd.readText().take(12_000)).append("\n\n")
        }

        // steering: .crab/steering/*.md (native format + SAF-attached files copied there)
        val steeringDir = File(projectRoot, ".crab/steering")
        val steeringFiles = steeringDir.listFiles()?.filter { it.isFile && it.extension == "md" }?.sortedBy { it.name } ?: emptyList()
        if (steeringFiles.isNotEmpty()) {
            append("## Файлы инструкций проекта (.crab/steering/)\n")
            steeringFiles.take(10).forEach { f ->
                append("### ${f.name}\n").append(f.readText().take(8_000)).append("\n\n")
            }
        }

        // memory
        val memory = File(projectRoot, ".crab/MEMORY.md")
        if (memory.isFile) {
            append("## Постоянная память проекта (.crab/MEMORY.md)\n").append(memory.readText().take(8_000)).append("\n\n")
        }

        // skills catalog
        val skillsDir = File(projectRoot, ".crab/skills")
        val skills = skillsDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (skills.isNotEmpty()) {
            append("## Доступные скиллы\n")
            skills.forEach { s ->
                val fm = ToolRegistry.readFrontmatter(File(s, "SKILL.md"))
                append("- ${s.name}: ${fm.second.ifEmpty { "см. load_skill" }}\n")
            }
            append("Загружай инструкции скилла инструментом load_skill перед его применением.\n\n")
        }

        if (attachedInstructionFiles.isNotEmpty()) {
            append("Подключённые пользователем файлы инструкций: ${attachedInstructionFiles.joinToString(", ")}\n\n")
        }

        append(
            "Проект: ${projectRoot.name}. Рабочая Linux-среда: Debian (PRoot), инструменты " +
                "python3/node/npm/git доступны, если установлены в окружении. " +
                "Контекстный бюджет ограничен: читай файлы по частям, используй search для поиска."
        )
    }
}
