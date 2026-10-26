package com.crabcode.ide.ai.tools

import com.crabcode.ide.ai.providers.ToolSchema
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolRegistryTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun registry(mode: AgentMode = AgentMode.AGENT, gate: ApprovalGate = NoApproval): ToolRegistry {
        val root = tmp.newFolder("proj")
        val home = tmp.newFolder("home")
        return ToolRegistry(
            projectRoot = root, userHome = home,
            commandRunner = { ToolRegistry.ProotCommandResult(0, "ok") },
            approvalGate = gate, mode = mode
        )
    }

    @Test
    fun `path traversal blocked`() {
        val r = registry()
        val blocked = listOf("../evil", "/etc/passwd", "a/../../b")
        blocked.forEach { p ->
            try {
                r.sandboxResolve(p)
                // /etc/passwd fails to resolve into either sandbox
                assertTrue("path $p should be blocked", p == "/etc/passwd" && false)
            } catch (e: ToolExecutionException) { /* expected */ }
        }
    }

    @Test
    fun `write read edit file flow`() = runTest {
        val r = registry()
        r.execute("write_file", """{"path":"src/a.py","content":"print(1)"}""")
        val content = r.execute("read_file", """{"path":"src/a.py"}""")
        assertTrue(content.contains("print(1)"))
        val edit = r.execute("edit_file", """{"path":"src/a.py","old_text":"1","new_text":"2"}""")
        assertTrue(edit.contains("изменён"))
        val after = r.execute("read_file", """{"path":"src/a.py"}""")
        assertTrue(after.contains("print(2)"))
    }

    @Test
    fun `plan mode blocks writes`() = runTest {
        val r = registry(mode = AgentMode.PLAN)
        try {
            r.execute("write_file", """{"path":"x.txt","content":"nope"}""")
            assertTrue(false)
        } catch (e: ToolExecutionException) {
            assertTrue(e.message!!.contains("Plan"))
        }
    }

    @Test
    fun `delete requires approval`() = runTest {
        var asked = 0
        val gate = object : ApprovalGate {
            override suspend fun request(approval: ApprovalRequest): Boolean { asked++; return false }
        }
        val root = tmp.newFolder("p2")
        File(root, "f.txt").writeText("x")
        val r = ToolRegistry(root, tmp.newFolder("h"), { ToolRegistry.ProotCommandResult(0, "") }, gate, AgentMode.AGENT)
        val res = r.execute("delete_path", """{"path":"f.txt"}""")
        assertEquals(1, asked)
        assertTrue(res.contains("отклонил"))
        assertTrue(File(root, "f.txt").exists())
    }

    @Test
    fun `search finds content`() = runTest {
        val root = tmp.newFolder("p3")
        val src = File(root, "src").apply { mkdirs() }
        File(src, "a.py").writeText("def alpha():\n    pass\n")
        File(src, "b.py").writeText("def beta():\n    pass\n")
        val r = ToolRegistry(root, tmp.newFolder("h"), { ToolRegistry.ProotCommandResult(0, "") }, NoApproval, AgentMode.AGENT)
        val res = r.execute("search", """{"pattern":"alpha"}""")
        assertTrue(res.contains("a.py"))
        assertTrue(!res.contains("b.py"))
    }

    @Test
    fun `schemas contain core tool set`() {
        val names = registry().schemas().map(ToolSchema::name)
        listOf(
            "read_file", "write_file", "edit_file", "list_dir", "search", "create_dir",
            "delete_path", "move_path", "copy_path", "run_command", "propose_command",
            "update_plan", "read_memory", "write_memory", "list_skills", "load_skill", "fetch_url"
        ).forEach { assertTrue("missing $it", it in names) }
    }

    @Test
    fun `memory and skills format matches original`() = runTest {
        val root = tmp.newFolder("p4")
        File(root, ".crab/skills/demo").mkdirs()
        File(root, ".crab/skills/demo/SKILL.md").writeText(
            "---\nname: demo\ndescription: Тестовый скилл\n---\nИнструкция"
        )
        val r = ToolRegistry(root, tmp.newFolder("h"), { ToolRegistry.ProotCommandResult(0, "") }, NoApproval, AgentMode.AGENT)
        val skills = r.execute("list_skills", "{}")
        assertTrue(skills.contains("demo"))
        assertTrue(skills.contains("Тестовый скилл"))
        val loaded = r.execute("load_skill", """{"name":"demo"}""")
        assertTrue(loaded.contains("Инструкция"))
        r.execute("write_memory", """{"note":"важно"}""")
        val mem = r.execute("read_memory", "{}")
        assertTrue(mem.contains("важно"))
    }
}
