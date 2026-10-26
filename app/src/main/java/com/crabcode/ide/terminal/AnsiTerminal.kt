package com.crabcode.ide.terminal

/**
 * Pure-Kotlin VT/xterm terminal emulator subset — the counterpart of the
 * desktop xterm.js stack of CrabCode.
 *
 * Supported: printable + wide chars, BS/HT/LF/CR/BEL, CSI cursor movement
 * (CUU/CUD/CUF/CUB/CUP/CNL/CPL/CHA), erase (ED/EL/ECH), insert/delete
 * (IL/DL/DCH/ICH), scroll (SU/SD), SGR (bold/italic/underline/strike/reverse,
 * 16/256/RGB colors, default fg/bg), DECSET/DECRST (25, 1049 alt buffer,
 * 2004 bracketed paste), OSC (title consumed), and scrollback.
 */
class AnsiTerminal(
    initialCols: Int = 80,
    initialRows: Int = 24,
    val scrollbackLimit: Int = 4000
) {
    data class Cell(val ch: Char, val style: Style) {
        companion object {
            val BLANK = Cell(' ', Style())
        }
    }

    data class Style(
        val fg: Int? = null,
        val bg: Int? = null,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val reverse: Boolean = false
    )

    class Row(val cells: Array<Cell>) {
        var wrapped: Boolean = false
        val width get() = cells.size
        operator fun get(i: Int) = cells.getOrElse(i) { Cell.BLANK }
        operator fun set(i: Int, c: Cell) { if (i in cells.indices) cells[i] = c }
        fun clear(style: Style) { java.util.Arrays.fill(cells, Cell(' ', style)) }
        fun copy(): Row = Row(cells.copyOf()).also { it.wrapped = wrapped }
    }

    var cols = initialCols; private set
    var rows = initialRows; private set
    var cursorX = 0; private set
    var cursorY = 0; private set
    var title: String = "crab"; private set
    var bell = false; private set

    private var mainBuffer = Array(rows) { Row(Array(cols) { Cell.BLANK }) }
    private var altBuffer: Array<Row>? = null
    private val scrollback = ArrayDeque<Row>()

    private var curStyle = Style()
    private var savedCursorX = 0
    private var savedCursorY = 0
    private var wrapPending = false
    var useAltBuffer = false; private set
    var cursorVisible = true; private set
    var bracketedPaste = false; private set
    private var applicationCursorKeys = false

    /** bumped on every visible mutation — views observe it */
    var revision = 0L; private set

    private val params = ArrayList<Int>()
    private var csiPrivate = false
    private var oscBuffer: StringBuilder? = null
    private var utf8Remaining = 0
    private var utf8Accumulator = 0

    // ------------------------------------------------------------------ feed

    fun feed(data: String) {
        var i = 0
        while (i < data.length) {
            val c = data[i]
            when {
                oscBuffer != null -> {
                    if (c == '\u0007' || (c == '\\' && data[i - 1] == '\u001b')) {
                        onOsc(oscBuffer.toString()); oscBuffer = null
                    } else oscBuffer!!.append(c)
                    i++
                }
                c == '\u001b' -> {
                    if (i + 1 < data.length) {
                        when (data[i + 1]) {
                            '[' -> { beginCsi(); i += 2 }
                            ']' -> { oscBuffer = StringBuilder(); i += 2 }
                            '7' -> { savedCursorX = cursorX; savedCursorY = cursorY; i += 2 }
                            '8' -> { cursorX = savedCursorX; cursorY = savedCursorY; i += 2 }
                            'M' -> { reverseIndex(); i += 2 }
                            '(' , ')' -> { i += 3 } // charset designation, consumed
                            else -> i += 1
                        }
                    } else i++
                }
                csiState -> handleCsiChar(c).also { i++ }
                c == '\r' -> { cursorX = 0; wrapPending = false; i++ }
                c == '\n' -> { lineFeed(); i++ }
                c == '\b' -> { if (cursorX > 0) cursorX--; wrapPending = false; i++ }
                c == '\t' -> { cursorX = ((cursorX / 8) + 1) * 8; if (cursorX >= cols) cursorX = cols - 1; i++ }
                c == '\u0007' -> { bell = true; i++ }
                c == '\u000e' || c == '\u000f' -> i++ // SO/SI ignored
                c < ' ' -> i++
                else -> { putChar(c); i++ }
            }
        }
        revision++
    }

    private fun putChar(c: Char) {
        if (cursorX >= cols) {
            if (wrapPending) {
                currentRow().wrapped = true
                lineFeed()
                cursorX = 0
                wrapPending = false
            } else {
                cursorX = cols - 1
            }
        }
        currentRow()[cursorX] = Cell(c, curStyle)
        cursorX++
        if (cursorX >= cols) wrapPending = true
    }

    private fun lineFeed() {
        if (cursorY == rows - 1) {
            scrollUp(1)
        } else cursorY++
    }

    private fun reverseIndex() {
        if (cursorY == 0) scrollDown(1) else cursorY--
    }

    private fun scrollUp(n: Int) {
        val buf = buffer()
        repeat(n) {
            if (!useAltBuffer) {
                if (scrollback.size >= scrollbackLimit) scrollback.removeFirst()
                scrollback.addLast(buf[0].copy())
            }
            for (y in 0 until rows - 1) buf[y] = buf[y + 1]
            buf[rows - 1] = Row(Array(cols) { Cell.BLANK })
        }
    }

    private fun scrollDown(n: Int) {
        val buf = buffer()
        repeat(n) {
            for (y in rows - 1 downTo 1) buf[y] = buf[y - 1]
            buf[0] = Row(Array(cols) { Cell.BLANK })
        }
    }

    // ---------------------------------------------------------------- CSI

    private val csiState: Boolean get() = params.isNotEmpty() || csiPrivate || collecting

    private var collecting = false

    private fun beginCsi() {
        params.clear(); csiPrivate = false; collecting = true
    }

    private fun handleCsiChar(c: Char) {
        when {
            c == '?' -> { csiPrivate = true; return }
            c.isDigit() -> {
                if (params.isEmpty()) params.add(0)
                params[params.size - 1] = params.last() * 10 + (c - '0')
                return
            }
            c == ';' -> { params.add(0); return }
            c == ':' -> { params.add(0); return }
        }
        collecting = false
        dispatchCsi(c)
        params.clear(); csiPrivate = false
    }

    private fun p(idx: Int = 0, def: Int = 1): Int = params.getOrNull(idx)?.takeIf { it != 0 } ?: def
    private fun p0(idx: Int = 0, def: Int = 0): Int = params.getOrNull(idx) ?: def

    private fun dispatchCsi(final: Char) {
        if (csiPrivate) {
            when (final) {
                'h' -> decSet(true)
                'l' -> decSet(false)
                else -> return
            }
            return
        }
        when (final) {
            'A' -> cursorY = (cursorY - p()).coerceAtLeast(0)
            'B' -> cursorY = (cursorY + p()).coerceAtMost(rows - 1)
            'C' -> { cursorX = (cursorX + p()).coerceAtMost(cols - 1); wrapPending = false }
            'D' -> { cursorX = (cursorX - p()).coerceAtLeast(0); wrapPending = false }
            'E' -> { cursorY = (cursorY + p()).coerceAtMost(rows - 1); cursorX = 0 }
            'F' -> { cursorY = (cursorY - p()).coerceAtLeast(0); cursorX = 0 }
            'G', '`' -> { cursorX = (p() - 1).coerceIn(0, cols - 1); wrapPending = false }
            'H', 'f' -> { cursorY = (p(0) - 1).coerceIn(0, rows - 1); cursorX = (p(1) - 1).coerceIn(0, cols - 1); wrapPending = false }
            'd' -> cursorY = (p() - 1).coerceIn(0, rows - 1)
            'J' -> eraseDisplay(p0())
            'K' -> eraseLine(p0())
            'X' -> { for (x in cursorX until cursorX + p()) currentRow()[x] = Cell(' ', curStyle) }
            'L' -> insertLines(p())
            'M' -> deleteLines(p())
            'P' -> deleteChars(p())
            '@' -> insertChars(p())
            'S' -> scrollUp(p())
            'T' -> scrollDown(p())
            'm' -> sgr()
            'r' -> { /* scroll region not tracked; accepted */ }
            's' -> { savedCursorX = cursorX; savedCursorY = cursorY }
            'u' -> { cursorX = savedCursorX; cursorY = savedCursorY }
            'c' -> feed("\u001B[?1;2c") // device attributes
        }
    }

    private fun decSet(set: Boolean) {
        for (param in params) {
            when (param) {
                25 -> cursorVisible = set
                1049 -> switchBuffer(set)
                2004 -> bracketedPaste = set
                1 -> applicationCursorKeys = set
            }
        }
    }

    private fun switchBuffer(toAlt: Boolean) {
        if (toAlt && !useAltBuffer) {
            altBuffer = Array(rows) { Row(Array(cols) { Cell.BLANK }) }
            mainBufferCopy = mainBuffer.map { it.copy() }.toTypedArray()
            mainBuffer = altBuffer!!
            useAltBuffer = true
            cursorX = 0; cursorY = 0
        } else if (!toAlt && useAltBuffer) {
            mainBuffer = mainBufferCopy!!
            altBuffer = null
            useAltBuffer = false
            cursorX = 0; cursorY = 0
        }
    }

    private var mainBufferCopy: Array<Row>? = null

    private fun eraseDisplay(mode: Int) {
        val buf = buffer()
        when (mode) {
            0 -> {
                eraseLine(0)
                for (y in cursorY + 1 until rows) buf[y].clear(curStyle)
            }
            1 -> {
                eraseLine(1)
                for (y in 0 until cursorY) buf[y].clear(curStyle)
            }
            2 -> for (y in 0 until rows) buf[y].clear(curStyle)
            3 -> scrollback.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        val row = currentRow()
        when (mode) {
            0 -> for (x in cursorX until cols) row[x] = Cell(' ', curStyle)
            1 -> for (x in 0..cursorX.coerceAtMost(cols - 1)) row[x] = Cell(' ', curStyle)
            2 -> row.clear(curStyle)
        }
    }

    private fun insertLines(n: Int) {
        val buf = buffer()
        for (i in 0 until n) {
            for (y in rows - 1 downTo cursorY + 1) buf[y] = buf[y - 1]
            buf[cursorY] = Row(Array(cols) { Cell.BLANK })
        }
    }

    private fun deleteLines(n: Int) {
        val buf = buffer()
        for (i in 0 until n) {
            for (y in cursorY until rows - 1) buf[y] = buf[y + 1]
            buf[rows - 1] = Row(Array(cols) { Cell.BLANK })
        }
    }

    private fun deleteChars(n: Int) {
        val row = currentRow()
        for (x in cursorX until cols) {
            row[x] = if (x + n < cols) row[x + n] else Cell(' ', curStyle)
        }
    }

    private fun insertChars(n: Int) {
        val row = currentRow()
        for (x in cols - 1 downTo cursorX + n) row[x] = row[x - n]
        for (x in cursorX until (cursorX + n).coerceAtMost(cols)) row[x] = Cell(' ', curStyle)
    }

    // ---------------------------------------------------------------- SGR

    private fun sgr() {
        if (params.isEmpty()) { curStyle = Style(); return }
        var i = 0
        while (i < params.size) {
            when (val v = params[i]) {
                0 -> curStyle = Style()
                1 -> curStyle = curStyle.copy(bold = true)
                3 -> curStyle = curStyle.copy(italic = true)
                4 -> curStyle = curStyle.copy(underline = true)
                7 -> curStyle = curStyle.copy(reverse = true)
                9 -> curStyle = curStyle.copy(strike = true)
                22 -> curStyle = curStyle.copy(bold = false)
                23 -> curStyle = curStyle.copy(italic = false)
                24 -> curStyle = curStyle.copy(underline = false)
                27 -> curStyle = curStyle.copy(reverse = false)
                29 -> curStyle = curStyle.copy(strike = false)
                in 30..37 -> curStyle = curStyle.copy(fg = v - 30)
                39 -> curStyle = curStyle.copy(fg = null)
                in 40..47 -> curStyle = curStyle.copy(bg = v - 40)
                49 -> curStyle = curStyle.copy(bg = null)
                in 90..97 -> curStyle = curStyle.copy(fg = v - 90 + 8)
                in 100..107 -> curStyle = curStyle.copy(bg = v - 100 + 8)
                38, 48 -> {
                    val color = extendedColor(i)
                    if (color != null) {
                        curStyle = if (v == 38) curStyle.copy(fg = color) else curStyle.copy(bg = color)
                        i += when (params.getOrNull(i + 1)) {
                            2 -> 4; 5 -> 2; else -> 1
                        }
                    }
                }
            }
            i++
        }
    }

    private fun extendedColor(idx: Int): Int? {
        return when (params.getOrNull(idx + 1)) {
            5 -> params.getOrNull(idx + 2)?.takeIf { it in 0..255 }?.let { COLOR_256_OFFSET + it }
            2 -> {
                val r = params.getOrNull(idx + 2) ?: return null
                val g = params.getOrNull(idx + 3) ?: return null
                val b = params.getOrNull(idx + 4) ?: return null
                0xff000000.toInt() or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
            }
            else -> null
        }
    }

    private fun onOsc(text: String) {
        val idx = text.indexOf(';')
        if (idx > 0) {
            when (text.substring(0, idx)) {
                "0", "2" -> title = text.substring(idx + 1)
            }
        }
    }

    // ---------------------------------------------------------------- api

    private fun buffer(): Array<Row> = mainBuffer
    private fun currentRow(): Row = mainBuffer[cursorY]

    fun resize(newCols: Int, newRows: Int) {
        if (newCols == cols && newRows == rows) return
        cols = newCols.coerceIn(8, 500)
        rows = newRows.coerceIn(4, 300)
        mainBuffer = Array(rows) { y ->
            if (y < mainBuffer.size) {
                val old = mainBuffer[y]
                Row(Array(cols) { x -> if (x < old.width) old[x] else Cell.BLANK })
            } else Row(Array(cols) { Cell.BLANK })
        }
        altBuffer = null; useAltBuffer = false
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = cursorY.coerceIn(0, rows - 1)
        revision++
    }

    /** Visible line including scrollback; scroll = lines scrolled up (0 = bottom). */
    fun line(index: Int, scroll: Int = 0): Row? {
        val sbSize = scrollback.size
        val fromScrollback = index < sbSize - scroll
        return if (fromScrollback) {
            scrollback.getOrNull(index + scroll)
        } else {
            val y = index - (sbSize - scroll)
            mainBuffer.getOrNull(y)
        }
    }

    val totalLines: Int get() = scrollback.size + rows
    val scrollbackSize: Int get() = scrollback.size

    /** Extract plain text of a line (for copy/search). */
    fun lineText(index: Int, scroll: Int = 0): String {
        val row = line(index, scroll) ?: return ""
        return row.cells.joinToString("") { it.ch.toString() }.trimEnd()
    }

    /** Whole screen+scrollback as text — used for search and exports. */
    fun allText(): String = (0 until totalLines).joinToString("\n") { lineText(it) }

    fun consumeBell(): Boolean {
        val was = bell; bell = false; return was
    }

    companion object {
        /** 256-color palette encoded as offset so Style stays serializable-friendly. */
        const val COLOR_256_OFFSET = 1_000_000

        /** Basic 16 ANSI colors (crab-dark tuned). */
        val ANSI_COLORS = intArrayOf(
            0xFF15161A.toInt(), 0xFFE0625A.toInt(), 0xFF1A7F37.toInt(), 0xFF9A6700.toInt(),
            0xFF2F81F7.toInt(), 0xFF8957E5.toInt(), 0xFF1B7C83.toInt(), 0xFFA6A6A6.toInt(),
            0xFF6E6E6E.toInt(), 0xFFF0524A.toInt(), 0xFF3FB950.toInt(), 0xFFD8A13A.toInt(),
            0xFF58A6FF.toInt(), 0xFFAB7DF8.toInt(), 0xFF39C5CF.toInt(), 0xFFECECEC.toInt()
        )

        fun styleColor(color: Int?, isFg: Boolean, default: Int): Int = when {
            color == null -> default
            color >= COLOR_256_OFFSET -> PALETTE_256[color - COLOR_256_OFFSET]
            color in 0..15 -> ANSI_COLORS[color]
            else -> color
        }

        val PALETTE_256: IntArray = IntArray(256) { i ->
            when {
                i < 16 -> ANSI_COLORS[i]
                i < 232 -> {
                    val j = i - 16
                    val steps = intArrayOf(0, 0x5f, 0x87, 0xaf, 0xd7, 0xff)
                    0xFF000000.toInt() or
                        (steps[(j / 36) % 6] shl 16) or
                        (steps[(j / 6) % 6] shl 8) or
                        steps[j % 6]
                }
                else -> {
                    val g = 8 + (i - 232) * 10
                    0xFF000000.toInt() or (g shl 16) or (g shl 8) or g
                }
            }
        }
    }
}
