package com.crabcode.ide.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileIconsTest {

    @Test
    fun `python icon for start.py`() {
        assertEquals("img/python.svg", FileIcons.forFile("start.py"))
    }

    @Test
    fun `icons for common languages`() {
        assertEquals("img/js.svg", FileIcons.forFile("app.js"))
        assertEquals("img/ts.svg", FileIcons.forFile("index.ts"))
        assertEquals("img/react-ts.svg", FileIcons.forFile("App.tsx"))
        assertEquals("img/rust.svg", FileIcons.forFile("main.rs"))
        assertEquals("img/go.svg", FileIcons.forFile("server.go"))
        assertEquals("img/brackets-orange.svg", FileIcons.forFile("page.html"))
        assertEquals("img/css.png", FileIcons.forFile("style.css"))
        assertEquals("img/node.svg", FileIcons.forFile("package.json"))
        assertEquals("img/brackets-blue.svg", FileIcons.forFile("data.json"))
        assertEquals("img/java.svg", FileIcons.forFile("Main.java"))
        assertEquals("img/kotlin.svg", FileIcons.forFile("Util.kt"))
        assertEquals("img/markdown.svg", FileIcons.forFile("README.md"))
        assertEquals("img/docker.svg", FileIcons.forFile("Dockerfile"))
        assertEquals("img/shell.svg", FileIcons.forFile("run.sh"))
    }

    @Test
    fun `special names win over extension`() {
        assertEquals("img/tsconfig.svg", FileIcons.forFile("tsconfig.json"))
        assertEquals("img/go.svg", FileIcons.forFile("go.mod"))
        assertEquals("img/rust.svg", FileIcons.forFile("Cargo.toml"))
    }

    @Test
    fun `unknown extension falls back to text`() {
        assertEquals("img/text.svg", FileIcons.forFile("data.xyz"))
    }

    @Test
    fun `folders map to original folder icons`() {
        assertEquals("img/floder/folder-src.svg", FileIcons.forFolder("src"))
        assertEquals("img/floder/folder-git.svg", FileIcons.forFolder(".git"))
        assertNotNull(FileIcons.forFolder("unusual_name"))
        assertEquals("img/floder/folder.svg", FileIcons.genericFolder())
    }
}
