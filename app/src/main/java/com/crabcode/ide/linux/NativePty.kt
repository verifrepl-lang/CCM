package com.crabcode.ide.linux

/**
 * JNI bridge to libcrabpty.so — the same role node-pty plays in desktop CrabCode.
 * External functions live in app/src/main/cpp/pty_jni.c.
 */
object NativePty {
    init {
        System.loadLibrary("crabpty")
    }

    /**
     * Fork with a PTY; the child immediately execve()s argv[0] with envp.
     * Returns [pid, masterFd]; pid == -1 with errno in slot 1 on failure.
     */
    external fun forkPty(rows: Int, cols: Int, argv: Array<String>, envp: Array<String>): IntArray

    external fun setWindowSize(masterFd: Int, rows: Int, cols: Int)

    external fun sendSignal(pid: Int, sig: Int)

    /** exit code if reaped, -1 while running. */
    external fun waitPidNoHang(pid: Int): Int
}
