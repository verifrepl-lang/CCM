package com.crabcode.ide.projects

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.crabcode.ide.core.auth.UserSpace
import com.crabcode.ide.data.ProjectEntity
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Project repository: working trees live under users/<hash>/projects/<id>.
 * Import/export uses the Android Storage Access Framework (no broad permissions).
 */
class ProjectRepository(
    private val context: Context,
    private val space: UserSpace,
    private val dao: com.crabcode.ide.data.ProjectDao
) {
    fun dir(entity: ProjectEntity): File = space.project(entity.id)

    suspend fun create(name: String): ProjectEntity {
        val id = UUID.randomUUID().toString()
        val entity = ProjectEntity(
            id = id, name = name.trim().ifEmpty { "Проект" },
            createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
        )
        dir(entity).mkdirs()
        // crab project layout
        File(dir(entity), ".crab").mkdirs()
        dao.upsert(entity)
        return entity
    }

    suspend fun delete(entity: ProjectEntity, confirmed: Boolean) {
        require(confirmed) { "Удаление проекта требует подтверждения" }
        dao.delete(entity.id)
        dir(entity).deleteRecursively()
    }

    suspend fun rename(entity: ProjectEntity, newName: String) {
        dao.upsert(entity.copy(name = newName.trim().ifEmpty { entity.name }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun touch(entity: ProjectEntity) {
        dao.upsert(entity.copy(updatedAt = System.currentTimeMillis()))
    }

    /** Import a ZIP (or a single file) picked with the system document picker. */
    suspend fun importFromUri(displayName: String, uri: Uri): Result<ProjectEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = create(displayName.substringBeforeLast('.').ifEmpty { "Импорт" })
            val targetDir = dir(entity)
            when {
                displayName.endsWith(".zip", true) -> {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ZipInputStream(input.buffered()).use { zip ->
                            var entry: ZipEntry? = zip.nextEntry
                            while (entry != null) {
                                val out = File(targetDir, entry.name)
                                if (!out.canonicalPath.startsWith(targetDir.canonicalPath + File.separator)) {
                                    throw SecurityException("Недопустимый путь в архиве: ${entry.name}")
                                }
                                if (entry.isDirectory) out.mkdirs()
                                else {
                                    out.parentFile?.mkdirs()
                                    FileOutputStream(out).use { zip.copyTo(it) }
                                }
                                entry = zip.nextEntry
                            }
                        }
                    } ?: error("Не удалось открыть выбранный файл")
                }
                else -> {
                    val name = displayName.ifBlank { "file.txt" }
                    val out = File(targetDir, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(out).use { input.copyTo(it) }
                    } ?: error("Не удалось открыть выбранный файл")
                }
            }
            touch(entity)
            entity
        }
    }

    /** Export the project as a ZIP into a user-picked location. */
    suspend fun exportToUri(entity: ProjectEntity, uri: Uri): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val root = dir(entity)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                ZipOutputStream(out.buffered()).use { zip ->
                    root.walkTopDown().filter { it.isFile }.forEach { f ->
                        val rel = f.relativeTo(root).path
                        zip.putNextEntry(ZipEntry(rel))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            } ?: error("Не удалось открыть место для записи")
        }
    }

    /** SAF directory import — copies the picked tree into a new project. */
    suspend fun importFromTree(displayName: String, treeUri: Uri): Result<ProjectEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val entity = create(displayName.ifEmpty { "Импорт" })
            val targetDir = dir(entity)
            val picked = DocumentFile.fromTreeUri(context, treeUri)
                ?: error("Невозможно открыть выбранный каталог")
            copyTree(picked, targetDir)
            touch(entity)
            entity
        }
    }

    private fun copyTree(source: DocumentFile, target: File) {
        target.mkdirs()
        source.listFiles().forEach { doc ->
            val dest = File(target, doc.name ?: "unnamed")
            if (doc.isDirectory) {
                copyTree(doc, dest)
            } else {
                context.contentResolver.openInputStream(doc.uri)?.use { input ->
                    FileOutputStream(dest).use { input.copyTo(it) }
                }
            }
        }
    }
}
