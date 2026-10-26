package com.crabcode.ide.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiTerminalTest {

    @Test
    fun `plain text renders`() {
        val t = AnsiTerminal(10, 3)
        t.feed("hi")
        assertEquals("hi", t.lineText(t.scrollbackSize))
    }

    @Test
    fun `newline and carriage return move cursor`() {
        val t = AnsiTerminal(10, 3)
        t.feed("abc\r\ndef")
        assertEquals("abc", t.lineText(t.scrollbackSize))
        assertEquals("def", t.lineText(t.scrollbackSize + 1))
    }

    @Test
    fun `cursor down and column positioning`() {
        val t = AnsiTerminal(20, 5)
        t.feed("one\r\n")
        t.feed("\u001B[3;5H") // row 3, col 5
        t.feed("X")
        assertEquals("    X", t.lineText(t.scrollbackSize + 2))
    }

    @Test
    fun `erase line modes`() {
        val t = AnsiTerminal(10, 2)
        t.feed("0123456789")
        t.feed("\u001B[1;4H\u001B[K") // cursor at col 4, erase to end
        assertEquals("012", t.lineText(t.scrollbackSize))
    }

    @Test
    fun `sgr bold color stored in cells`() {
        val t = AnsiTerminal(10, 2)
        t.feed("\u001B[1;31mR\u001B[0mn")
        val row = t.line(t.scrollbackSize)!!
        assertTrue(row.cells[0].style.bold)
        assertEquals(1, row.cells[0].style.fg)
        assertEquals(null, row.cells[1].style.fg)
        assertEquals(false, row.cells[1].style.bold)
    }

    @Test
    fun `sgr 256 color`() {
        val t = AnsiTerminal(10, 2)
        t.feed("\u001B[38;5;208mX")
        val fg = t.line(t.scrollbackSize)!!.cells[0].style.fg
        assertEquals(AnsiTerminal.COLOR_256_OFFSET + 208, fg)
        assertEquals(0xFFFF8700.toInt(), AnsiTerminal.styleColor(fg, true, 0))
    }

    @Test
    fun `sgr rgb color`() {
        val t = AnsiTerminal(10, 2)
        t.feed("\u001B[38;2;255;0;255mX")
        val fg = t.line(t.scrollbackSize)!!.cells[0].style.fg
        assertEquals(0xFFFF00FF.toInt(), AnsiTerminal.styleColor(fg, true, 0))
    }

    @Test
    fun `scrolling pushes lines into scrollback`() {
        val t = AnsiTerminal(10, 3)
        for (i in 1..6) t.feed("line$i\r\n")
        assertTrue(t.scrollbackSize >= 3)
        assertEquals("line1", t.lineText(0))
    }

    @Test
    fun `alt buffer switch and restore`() {
        val t = AnsiTerminal(10, 3)
        t.feed("main")
        t.feed("\u001B[?1049h")
        t.feed("alt")
        assertTrue(t.useAltBuffer)
        t.feed("\u001B[?1049l")
        assertTrue(!t.useAltBuffer)
        assertEquals("main", t.lineText(t.scrollbackSize))
    }

    @Test
    fun `osc title consumed`() {
        val t = AnsiTerminal(10, 2)
        t.feed("\u001B]0;my title\u0007plain")
        assertEquals("my title", t.title)
        assertEquals("plain", t.lineText(t.scrollbackSize))
    }

    @Test
    fun `resize keeps content`() {
        val t = AnsiTerminal(10, 2)
        t.feed("0123456789")
        t.resize(40, 10)
        assertEquals("0123456789", t.lineText(t.scrollbackSize))
        assertEquals(40, t.cols)
    }

    @Test
    fun `insert and delete chars`() {
        val t = AnsiTerminal(10, 2)
        t.feed("abcdef")
        t.feed("\u001B[1;1H\u001B[2P") // delete 2 chars at col1 → cdef
        assertEquals("cdef", t.lineText(t.scrollbackSize))
        t.feed("\u001B[1;1H\u001B[2@") // insert 2 blanks at col1
        assertEquals("  cdef", t.lineText(t.scrollbackSize))
    }

    @Test
    fun `bell flag`() {
        val t = AnsiTerminal(10, 2)
        t.feed("\u0007")
        assertTrue(t.consumeBell())
        assertTrue(!t.consumeBell())
    }
}
