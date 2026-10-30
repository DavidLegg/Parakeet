package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.RmMode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.glob
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.isEmpty
import gov.nasa.jpl.parakeet.kernel.Effect
import gov.nasa.jpl.parakeet.utilities.named
import java.nio.file.Path
import java.util.EnumSet
import kotlin.io.path.Path
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.time.Instant

object FileSystemModelOperations {
    /*
     * Read Operations
     */

    /**
     * Get the root [Directory], assumed to be at path "/"
     */
    context (_: ResourceScope)
    fun FileSystemModel.getRoot() = get(Path("/")) as Directory

    /**
     * Return all file nodes matching the glob [pattern].
     *
     * @see [Directory.glob]
     */
    context (_: ResourceScope)
    fun FileSystemModel.glob(pattern: Path): Map<Path, FileSystemNode> {
        // TODO: For performance, we may want to extract the longest prefix of pattern that's literal, and `get` that.
        //   Then we can glob-search down from that.
        //   If we're dealing with a model that uses more sophisticated split-backings, this would sample fewer cells.
        return if (pattern.isAbsolute) {
            // Access the absolute root path, then glob the pattern
            (get(Path("/")) as Directory).glob(pattern.relativeTo(Path("/")))
        } else {
            // Access the current working dir, then glob the pattern
            (get(Path("")) as Directory).glob(pattern)
        }
    }

    // TODO: Other common file operations, like ls and globbing?
    // Should these be written as FSN operations, and then just lifted to model operations, maybe?

    /*
     * Write Operations
     */

    // TODO: We never use post-conditions. Consider just moving pre-conditions into the caller, and removing this method.
    /**
     * Convenience extension of [FileSystemModel.change], with explicit pre- and post-condition checks.
     * These checks are run both in the task and in the effect.
     * The checks run in the task can crash the task, and the precondition can abort the change if this happens.
     * The checks run in the effect will corrupt the file system if the preconditions change due to concurrent effects.
     */
    context (_: TaskScope)
    fun FileSystemModel.change(
        path: Path,
        effect: Effect<FileSystemNode?>,
        checkPreconditions: (FileSystemNode?) -> Unit = {},
        checkPostConditions: (FileSystemNode?) -> Unit = {},
    ) {
        checkPreconditions(this[path])
        change(path, { target: FileSystemNode? ->
            checkPreconditions(target)
            effect(target).also { checkPostConditions(it) }
        }.named(effect::toString))
        checkPostConditions(this[path])
    }

    /**
     * Make a directory, similar to the `mkdir` shell command.
     *
     * Throws an error if [path]'s parent does not exist, or is not a directory.
     * Throws an error if [path] already exists.
     */
    context (_: TaskScope)
    fun FileSystemModel.mkdir(path: Path) {
        val newDir = Directory(path.name, now())
        change(path, { _: FileSystemNode? -> newDir }.named { "mkdir" }, checkPreconditions = { target ->
            require(target == null) {
                "Path $path already exists"
            }
        })
    }

    /**
     * Make a directory, similar to the `mkdir -p` shell command.
     *
     * Silent no-op if [path] already exists and is a directory.
     * Throws an error if [path] or any subpath thereof is a file.
     */
    context (_: TaskScope)
    fun FileSystemModel.mkdirP(path: Path) {
        val now = now()
        var existingDir = get(path.root) as Directory
        for ((i, component) in path.withIndex()) {
            // We're looking for the highest directory we need to create
            val nextDir = existingDir[component.name]
            if (nextDir != null) {
                require(nextDir is Directory) {
                    "Found file at ${path.subpath(0, i + 1)} when a directory was expected!"
                }
                existingDir = nextDir
            } else {
                // We found where we need to start building directories!
                // To save a little work, roll the directories up from the bottom instead of building empty dirs
                // and then setting children in those empty dirs.
                // That means building an empty dir for the final component in the path,
                // and a one-child dir for every component from i until next-to-last in path.
                val newDir = path.subpath(i, path.nameCount - 1).reversed().fold(Directory(path.name, now)) {
                    childDir, component -> Directory(component.name, now, children = mapOf(childDir.name to childDir))
                }
                // Finally, we set that newDir at its path, which includes newDir's name itself
                change(path.subpath(i + 1), { _: FileSystemNode? -> newDir }.named { "mkdir -P" }, checkPreconditions = {
                    require(it == null) {
                        "Path $path already exists"
                    }
                })
                // Having done that, we're done with this method entirely
                return
            }
        }
        // Note that if we exit the loop without returning explicitly, it's because the directory exists already.
        // In this case, a graceful exit is also the right behavior. Nothing to do here.
    }

    enum class RmMode {
        Files,
        EmptyDirectories,
        NonEmptyDirectories,
        CorruptedNodes,
    }
    /** Default behavior for `rm` command, only removes [File] nodes. */
    val RM_FILE_MODE = EnumSet.of(Files)
    /** Default behavior for `rmdir` command, only removes empty [Directory] nodes. */
    val RM_DIR_MODE = EnumSet.of(EmptyDirectories)
    /** Default behavior for `rm -d` command, only removes files and empty directories */
    val RM_LEAF_MODE = EnumSet.of(Files, EmptyDirectories)
    /** Default behavior for `rm -r` command, removes anything */
    val RM_RECURSIVE_MODE = EnumSet.of(Files, CorruptedNodes, EmptyDirectories, NonEmptyDirectories)
    /** Special mode for [FileSystemModel], only removes [Corrupted] nodes. */
    val RM_CORRUPTED_MODE = EnumSet.of(CorruptedNodes)

    /** Approximates the shell command `rmdir` */
    context (_: TaskScope)
    fun FileSystemModel.rmdir(path: Path) = rm(path, RM_DIR_MODE)

    /** Approximates the shell command `rm -d` */
    context (_: TaskScope)
    fun FileSystemModel.rmLeaf(path: Path) = rm(path, RM_LEAF_MODE)

    /** Approximates the shell command `rm -r` */
    context (_: TaskScope)
    fun FileSystemModel.rmRecursive(path: Path) = rm(path, RM_RECURSIVE_MODE)

    /** Special version of `rm` which only removes a [Corrupted] node. */
    context (_: TaskScope)
    fun FileSystemModel.rmCorrupted(path: Path) = rm(path, RM_CORRUPTED_MODE)

    /**
     * Remove a [FileSystemNode].
     *
     * Without arguments, approximates the shell command `rm`
     *
     * @param permissions Can be used to modify which kinds of nodes may be removed.
     */
    context (_: TaskScope)
    fun FileSystemModel.rm(path: Path, permissions: EnumSet<RmMode> = RM_FILE_MODE) {
        change(path, { _: FileSystemNode? -> null }.named {
            // Lazily compute the name by trying to infer what kind of removal we're running
            when (permissions) {
                RM_FILE_MODE -> "rm"
                RM_DIR_MODE -> "rmdir"
                RM_LEAF_MODE -> "rm -d"
                RM_RECURSIVE_MODE -> "rm -r"
                RM_CORRUPTED_MODE -> "rm (corrupted)"
                else -> "rm (custom: $permissions)"
            }
        }, checkPreconditions = {
            if (it != null) {
                val removalKind = when (it) {
                    is File -> Files
                    is Corrupted -> CorruptedNodes
                    is Directory -> if (it.isEmpty()) EmptyDirectories else NonEmptyDirectories
                }
                require(removalKind in permissions) {
                    "rm failed: $path requires $removalKind permissions, but given permissions were $permissions"
                }
            }
        })
    }

    /** Approximates the shell command `touch` */
    context (_: TaskScope)
    fun FileSystemModel.touch(path: Path) {
        val now = now()
        change(path, { target: FileSystemNode? ->
            target?.modifiedAt(now) ?: File(path.name, now)
        }.named { "touch" })
    }

    enum class WriteMode {
        /**
         * Create a new file. Error if the file already exists.
         */
        CreateOnly,
        /**
         * Create a new file, or overwrite the file if it already exists.
         * Replaces both [File.content] and [File.sizeBytes].
         */
        CreateOrOverwrite,
        /**
         * Create a new file, or append to the file if it already exists.
         * Appends to [File.content] and adds to [File.sizeBytes] independently.
         * No guarantee is made that `File.content.size` will agree with `File.sizeBytes`.
         */
        Append,
    }

    /**
     * Simulate writing a file at [path].
     *
     * @see WriteMode
     */
    context (_: TaskScope)
    fun FileSystemModel.write(
        path: Path,
        content: ByteArray? = null,
        sizeBytes: Int = content?.size ?: 0,
        writeMode: WriteMode = WriteMode.CreateOrOverwrite,
    ) {
        val now = now()
        change(
            path,
            { target: FileSystemNode? ->
                if (target == null) {
                    File(path.name, now, now, content, sizeBytes)
                } else {
                    // Safety: this cast is safe because of the precondition below
                    target as File
                    when (writeMode) {
                        WriteMode.CreateOnly -> throw AssertionError("Impossible code path")
                        WriteMode.CreateOrOverwrite -> target.copy(modifiedTime = now, content = content, sizeBytes = sizeBytes)
                        WriteMode.Append -> target.copy(
                            modifiedTime = now,
                            content = content?.let {
                                if (target.content == null) {
                                    // We'll likely never combine null-content and non-null-content file modeling.
                                    // Just do a best-effort merge for this case.
                                    content
                                } else {
                                    // Genuine file append operation if both the existing file and the append operation have content.
                                    ByteArray(content.size + target.content.size).also {
                                        target.content.copyInto(it)
                                        content.copyInto(it, target.content.size)
                                    }
                                }
                            } ?: target.content,
                            // Regardless of content, just add the sizes. This covers the common case of size-only modeling.
                            sizeBytes = sizeBytes + target.sizeBytes,
                        )
                    }
                }
            },
            checkPreconditions = {
                when (writeMode) {
                    WriteMode.CreateOnly -> require(it == null) {
                        "Cannot write to $path - it exists and write mode is ${WriteMode.CreateOnly}"
                    }
                    WriteMode.CreateOrOverwrite, WriteMode.Append -> require(it is File?) {
                        "Cannot write to $path - it exists and is not a file"
                    }
                }
            },
        )
    }

    /**
     * Simulate appending to a file at [path].
     */
    context (_: TaskScope)
    fun FileSystemModel.append(
        path: Path,
        content: ByteArray? = null,
        sizeBytes: Int = content?.size ?: 0,
    ) = write(path, content, sizeBytes, WriteMode.Append)

    /**
     * Approximation of shell command `mv`.
     *
     * @see cp
     */
    context (_: TaskScope)
    fun FileSystemModel.mv(src: Path, dst: Path) {
        val srcNode = this[src]
        requireNotNull(srcNode) {
            "Cannot mv $src - not found"
        }
        val dstNode = this[dst]
        val resolvedDst: Path
        val resolvedDstNode: FileSystemNode?
        when (dstNode) {
            // If dst is an existing directory, mv into that directory
            is Directory -> {
                resolvedDst = dst / src.name
                resolvedDstNode = dstNode[src.name]
            }
            // If dst names an existing file, or nothing at all, mv to that exact path
            is File, null -> {
                resolvedDst = dst
                resolvedDstNode = dstNode
            }
            // If dst is corrupted, fail instead of guessing whether it's a directory or a file
            is Corrupted -> throw IllegalArgumentException("mv failed - dst $dst is corrupted")
        }
        // If the resolved destination path is src itself, this mv is a no-op. Quit early.
        if (resolvedDst == src) return
        // Otherwise, make sure we aren't trying to move a directory into a sub-dir of itself.
        require(!resolvedDst.startsWith(src)) {
            "Cannot mv $src to its own sub-directory $resolvedDst"
        }
        if (resolvedDstNode == null) {
            // If the destination doesn't exist, just check that the destination directory does exist
            require(this[resolvedDst.parent] is Directory) {
                "Cannot mv $src to $dst - ${resolvedDst.parent} is not a directory"
            }
        } else {
            // resolvedDst exists, ensure it's compatible for overwriting
            when (srcNode) {
                is File -> require(resolvedDstNode is File) {
                    "Cannot mv file $src to overwrite non-file $dst"
                }
                is Directory -> {
                    require(resolvedDstNode is Directory) {
                        "Cannot mv directory $src to overwrite non-directory $dst"
                    }
                    require(resolvedDstNode.isEmpty()) {
                        "Cannot mv directory $src to overwrite non-empty directory $dst"
                    }
                }
                is Corrupted -> throw IllegalArgumentException("Cannot mv corrupted node $src to overwrite $dst")
            }
        }

        // Moving a file doesn't generally affect the timestamps
        val newDstNode = srcNode.renamed(resolvedDst.name)
        val now = now()
        // Remove the node at src
        change(src, { _: FileSystemNode? -> null }.named { "mv (remove src)" })
        // Add the node at resolvedDst, and update the directory's modified time
        change(resolvedDst.parent, { d: FileSystemNode? ->
            (d as Directory).copy(modifiedTime = now, children = d.children.toMutableMap().also {
                it[newDstNode.name] = newDstNode
            })
        }.named { "mv (write dst)" })
    }

    /**
     * Approximation of shell command `cp`.
     *
     * @see mv
     */
    context (_: TaskScope)
    fun FileSystemModel.cp(src: Path, dst: Path, recursive: Boolean = false) {
        val target = this[src]
        requireNotNull(target) {
            "Cannot cp $src - not found"
        }
        if (target is Directory) {
            require(recursive) {
                "Cannot cp directory $src because recursive mode is not enabled"
            }
        }
        val now = now()
        val destination = this[dst]
        val resolvedDst: Path
        val newCreatedTime: Instant
        when (destination) {
            // If dst is an existing directory, mv into that directory
            is Directory -> {
                resolvedDst = dst / target.name
                newCreatedTime = now
            }
            // If dst names an existing file, or nothing at all, cp to that exact path
            is File -> {
                require (target is File) {
                    "Cannot cp $src (non-file node) to $dst (a file)"
                }
                resolvedDst = dst
                // Creation times are not part of POSIX, but typical Linux systems reuse the inode in this case.
                // This leads to preserving the destination's created time.
                newCreatedTime = destination.createdTime
            }
            null -> {
                resolvedDst = dst
                newCreatedTime = now
            }
            // If dst is corrupted, fail instead of guessing whether it's a directory or a file
            is Corrupted -> {
                throw IllegalArgumentException("cp failed - dst $dst is corrupted")
            }
        }

        fun FileSystemNode.recursivelySetTimes(topLevelCreatedTime: Instant = now): FileSystemNode = when (this) {
            is File -> this.copy(createdTime = topLevelCreatedTime, modifiedTime = now)
            is Directory -> this.copy(createdTime = topLevelCreatedTime, modifiedTime = now, children = children.mapValues { it.value.recursivelySetTimes() })
            is Corrupted -> this.copy(createdTime = topLevelCreatedTime, modifiedTime = now)
        }

        val newTarget = target.renamed(resolvedDst.name).recursivelySetTimes(newCreatedTime)
        change(resolvedDst, { _: FileSystemNode? -> newTarget }.named { "cp from $src" },
            checkPreconditions = {
                require(it == null || (it is File && newTarget is File)) {
                    "Cannot cp $src to $dst - move would overwrite a directory or corrupted node"
                }
            })
    }


    /**
     * Approximation of shell command `cp -R`.
     *
     * @see mv
     */
    context (_: TaskScope)
    fun FileSystemModel.cpRecursive(src: Path, dst: Path) = cp(src, dst, recursive = true)
}

/**
 * Return the subpath of [this] [Path] up to and including component [end].
 *
 * The result will be absolute or relative to match [this].
 */
private fun Path.subpath(end: Int): Path {
    val relativePath = subpath(0, end)
    // Somewhat awkwardly, we need to rebuild an absolute path if we started with an absolute path
    return root?.let { it / relativePath } ?: relativePath
}

private fun FileSystemNode.renamed(name: String): FileSystemNode = when (this) {
    is File -> copy(name = name)
    is Directory -> copy(name = name)
    is Corrupted -> copy(name = name)
}

private fun FileSystemNode.createdAt(time: Instant): FileSystemNode = when (this) {
    is File -> copy(createdTime = time)
    is Directory -> copy(createdTime = time)
    is Corrupted -> copy(createdTime = time)
}

private fun FileSystemNode.modifiedAt(time: Instant): FileSystemNode = when (this) {
    is File -> copy(modifiedTime = time)
    is Directory -> copy(modifiedTime = time)
    is Corrupted -> copy(modifiedTime = time)
}
