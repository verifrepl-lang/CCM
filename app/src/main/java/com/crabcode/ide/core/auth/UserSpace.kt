package com.crabcode.ide.core.auth

import android.content.Context
import java.io.File

/**
 * Per-user storage layout. Everything is namespaced under filesDir/users/<hash>:
 *
 * users/<hash>/
 * ├── projects/<projectId>/        — project working trees
 * ├── home/                        — Linux $HOME inside PRoot
 * ├── config/                      — providers.json, mcp.json, settings.json
 * ├── db/chats.db                  — Room database (chats, sessions, messages)
 * └── downloads/                   — OTA artifacts (rootfs, apk)
 */
data class UserSpace(val root: File) {
    val projects: File get() = File(root, "projects")
    val home: File get() = File(root, "home")
    val config: File get() = File(root, "config")
    val db: File get() = File(root, "db")
    val downloads: File get() = File(root, "downloads")

    fun ensure() {
        listOf(projects, home, config, db, downloads).forEach { it.mkdirs() }
    }

    fun project(id: String): File = File(projects, id)

    companion object {
        fun forAccount(context: Context, account: UserAccount): UserSpace {
            val space = UserSpace(File(File(context.filesDir, "users"), GoogleAuth.userHash(account.sub)))
            space.ensure()
            return space
        }
    }
}
