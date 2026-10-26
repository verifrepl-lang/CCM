package com.crabcode.ide.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntaxHighlighterTest {

    @Test
    fun `language detection`() {
        assertEquals("python", SyntaxHighlighter.languageOf("start.py"))
        assertEquals("typescript", SyntaxHighlighter.languageOf("App.tsx"))
        assertEquals("rust", SyntaxHighlighter.languageOf("main.rs"))
        assertEquals("text", SyntaxHighlighter.languageOf("file.xyz"))
    }

    @Test
    fun `python keyword and string`() {
        val tokens = SyntaxHighlighter.tokenize("def hello():", "python")
        val defTok = tokens.first { it.text == "def" }
        assertEquals(SyntaxHighlighter.TokenType.KEYWORD, defTok.type)
    }

    @Test
    fun `string token`() {
        val tokens = SyntaxHighlighter.tokenize("x = \"value\"", "python")
        assertTrue(tokens.any { it.type == SyntaxHighlighter.TokenType.STRING && it.text.contains("value") })
    }

    @Test
    fun `comment token`() {
        val tokens = SyntaxHighlighter.tokenize("# note", "python")
        assertEquals(SyntaxHighlighter.TokenType.COMMENT, tokens.first().type)
    }

    @Test
    fun `number token`() {
        val tokens = SyntaxHighlighter.tokenize("42", "python")
        assertEquals(SyntaxHighlighter.TokenType.NUMBER, tokens.first().type)
    }

    @Test
    fun `kotlin annotation`() {
        val tokens = SyntaxHighlighter.tokenize("@Serializable", "kotlin")
        assertTrue(tokens.any { it.type == SyntaxHighlighter.TokenType.ANNOTATION })
    }

    @Test
    fun `unknown language plain`() {
        val tokens = SyntaxHighlighter.tokenize("anything", "text")
        assertEquals(1, tokens.size)
        assertEquals(SyntaxHighlighter.TokenType.PLAIN, tokens[0].type)
    }
}
