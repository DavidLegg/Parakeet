package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path

/**
 * Convenience operations over [FileSystemModel.get] and [FileSystemModel.change].
 */
object FileSystemOperations {
    context (_: ResourceScope)
    fun FileSystemModel.exists(path: Path) = get(path) != null

    context (_: ResourceScope)
    fun FileSystemModel.isFile(path: Path) = get(path) is File

    context (_: ResourceScope)
    fun FileSystemModel.isDirectory(path: Path) = get(path) is Directory

    /** Names of the children of the directory at [path]. */
    context (_: ResourceScope)
    fun FileSystemModel.list(path: Path): Set<String> = when (val node = get(path)) {
        is Directory -> node.children.keys
        is File -> throw NotDirectoryException(path.toString())
        null -> throw NoSuchFileException(path.toString())
    }

    context (_: ResourceScope)
    fun FileSystemModel.stat(path: Path): FileNode = get(path) ?: throw NoSuchFileException(path.toString())

    context (_: ResourceScope)
    fun FileSystemModel.readContent(path: Path): FileContent? = when (val node = stat(path)) {
        is File -> node.content
        is Directory -> throw java.nio.file.FileSystemException(path.toString(), null, "Is a directory")
    }

    /** Fails with [FileAlreadyExistsException] if [path] already exists. */
    context (_: TaskScope)
    suspend fun FileSystemModel.createDirectory(path: Path) {
        val t = now()
        change(path) { old ->
            if (old != null) throw FileAlreadyExistsException(path.toString())
            Directory(t, t)
        }
    }

    /** Like `mkdir -p`: creates [path] and any missing ancestors. Fails if a component exists as a file. */
    context (_: TaskScope)
    suspend fun FileSystemModel.createDirectories(path: Path) {
        var current = path.root ?: throw IllegalArgumentException("File system paths must be absolute: $path")
        for (name in path.normalize()) {
            current = current.resolve(name)
            val existing = get(current)
            if (existing is File) throw FileAlreadyExistsException(current.toString())
            if (existing == null) createDirectory(current)
        }
    }

    /**
     * Create or overwrite the file at [path], preserving its creation time on overwrite.
     * [size] defaults to the size of [content]. Fails if [path] is a directory.
     */
    context (_: TaskScope)
    suspend fun FileSystemModel.writeFile(path: Path, content: FileContent? = null, size: Long = content?.size?.toLong() ?: 0L) {
        val t = now()
        change(path) { old ->
            when (old) {
                is Directory -> throw FileAlreadyExistsException(path.toString(), null, "Is a directory")
                is File -> File(old.createdTime, t, size, content)
                null -> File(t, t, size, content)
            }
        }
    }

    /** Delete a file or empty directory. Fails if [path] is missing or a non-empty directory. */
    context (_: TaskScope)
    suspend fun FileSystemModel.delete(path: Path) {
        change(path) { old ->
            when {
                old == null -> throw NoSuchFileException(path.toString())
                old is Directory && old.children.isNotEmpty() -> throw DirectoryNotEmptyException(path.toString())
                else -> null
            }
        }
    }

    /** Delete [path] and everything under it. Deleting a missing path is a no-op. */
    context (_: TaskScope)
    suspend fun FileSystemModel.deleteRecursively(path: Path) = change(path) { null }
}
