package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModelOperations.RmMode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.isEmpty
import gov.nasa.jpl.parakeet.kernel.Effect
import gov.nasa.jpl.parakeet.utilities.named
import java.nio.file.Path
import java.util.EnumSet
import kotlin.io.path.name

object FileSystemModelOperations {
    /*
     * Read Operations
     */

    /** Access [path] in the current value of this model. */
    context (_: ResourceScope)
    operator fun FileSystemModel.get(path: Path): FileSystemNode? = getValue()[path]

    // TODO: Other common file operations, like ls and globbing?
    // Should these be written as FSN operations, and then just lifted to model operations, maybe?

    /*
     * Write Operations
     */

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
        var existingDir = getValue()
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
                change(path.subpath(0, i + 1), { _: FileSystemNode? -> newDir }.named { "mkdir -P" }, checkPreconditions = {
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
        change(path, { target: FileSystemNode? -> when (target) {
            is File -> target.copy(modifiedTime = now)
            is Directory -> target.copy(modifiedTime = now)
            is Corrupted -> target.copy(modifiedTime = now)
            null -> File(path.name, now)
        } }.named { "touch" })
    }
}
