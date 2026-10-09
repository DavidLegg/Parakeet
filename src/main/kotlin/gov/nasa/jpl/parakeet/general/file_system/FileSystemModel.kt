package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.Resource
import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.emit
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.resource
import gov.nasa.jpl.parakeet.foundation.resources.safelyApplyConcurrentEffects
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.utilities.named
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import kotlin.time.Instant
import kotlin.reflect.typeOf

/**
 * A basic model of a hierarchical file system, as a single discrete resource whose value is the root [Directory].
 *
 * Paths must be absolute; they are normalized before use. There is no working directory.
 * [get] and [change] are the only primitives. See the extension functions in [FileSystemOperations] for convenient operations.
 */
interface FileSystemModel : DiscreteResource<Directory> {
    // TODO: (Design change) Turn this into an extension method, since getValue() will give us the root directory and we need to call it anyways
    /**
     * The node at [path], or null if any component is missing, or an intermediate component is a file.
     */
    context (_: ResourceScope)
    fun get(path: Path): FileNode?

    /**
     * Atomically read-modify-write the node at [path].
     * [f] receives the current node (or null if none) and returns the new node, or null to delete.
     * Deleting a missing path is a no-op. The root may be replaced (by a directory), but not deleted.
     * A non-null result requires that the parent exists and is a directory.
     *
     * [f] runs inside the effect, possibly several times and in several orderings.
     * It must be a pure, deterministic function of its argument: don't read resources or call `now()` inside it.
     *
     * Throws an [IOException] (leaving the file system untouched) if the change is invalid.
     */
    context (_: TaskScope)
    suspend fun change(path: Path, f: (FileNode?) -> FileNode?)
}

/**
 * Create a [FileSystemModel].
 * If [initialRoot] is omitted, the file system starts as an empty root, created at the current simulation time.
 *
 * A fault caused by non-commuting concurrent effects is logged to stderr and the batch of effects is dropped,
 * reverting the file system to its value before the batch.
 */
context (_: InitScope)
fun FileSystemModel(name: String, initialRoot: Directory = now().let { Directory(it, it) }): FileSystemModel {
    val state = resource(
        name,
        Discrete(initialRoot),
        typeOf<Discrete<Directory>>(),
        ::applyConcurrentFileSystemEffects,
    )

    return object : FileSystemModel, DiscreteResource<Directory> by state {
        context (_: ResourceScope)
        override fun get(path: Path): FileNode? = state.getValue().lookup(path.segments())

        // TODO: Inherit the name from f.toString(), so we can use more descriptive names
        // TODO: I think we may need to re-think how to do changes such that errors fault the caller instead of the resource
        context (_: TaskScope)
        override suspend fun change(path: Path, f: (FileNode?) -> FileNode?) {
            val segments = path.segments()
            val timestamp = now()
            // Validate against the current value first, so an invalid change fails the calling task, rather than faulting the resource.
            state.getValue().change(segments, timestamp, f)
            state.emit({ d: Directory -> d.change(segments, timestamp, f) }
                .named { "Change $path" })
        }

        override val name: Name = state.name
        override fun toString() = state.name.toString()
    }
}

private fun applyConcurrentFileSystemEffects(
    effects: List<ResourceEffect<Discrete<Directory>>>,
    initialValue: Result<FullDynamics<Discrete<Directory>>>,
): Result<FullDynamics<Discrete<Directory>>> {
    val result = safelyApplyConcurrentEffects(effects, initialValue)
    return result.exceptionOrNull()?.let {
        // TODO: (Bug) - Don't write to System.err - we want to write to simulation stderr, which may not be possible here since we don't have a task scope
        System.err.println("File system fault from concurrent effects; reverting to last known good state. Cause: $it")
        initialValue
    } ?: result
}

internal fun Path.segments(): List<String> {
    if (!isAbsolute) throw IllegalArgumentException("File system paths must be absolute: $this")
    return normalize().map { it.toString() }
}

internal fun Directory.lookup(segments: List<String>): FileNode? {
    var node: FileNode = this
    for (segment in segments) node = (node as? Directory)?.children?.get(segment) ?: return null
    return node
}

internal fun Directory.change(segments: List<String>, timestamp: Instant, f: (FileNode?) -> FileNode?): Directory {
    if (segments.isEmpty()) {
        val new = f(this) ?: throw IOException("Cannot delete the root directory")
        return new as? Directory ?: throw NotDirectoryException("/")
    }
    val old = lookup(segments)
    val new = f(old)
    if (old == null && new == null) return this
    return replace(segments, 0, old, new, timestamp)
}

private fun Directory.replace(segments: List<String>, i: Int, old: FileNode?, new: FileNode?, timestamp: Instant): Directory {
    val name = segments[i]
    if (i == segments.lastIndex) {
        val newChildren = if (new == null) children - name else children + (name to new)
        val namesChanged = (old == null) != (new == null)
        val kindChanged = old != null && new != null && old::class != new::class
        return copy(
            modifiedTime = if (namesChanged || kindChanged) timestamp else modifiedTime,
            children = newChildren,
        )
    }
    val path = "/" + segments.take(i + 1).joinToString("/")
    val child = when (val c = children[name]) {
        is Directory -> c
        is File -> throw NotDirectoryException(path)
        null -> throw NoSuchFileException(path)
    }
    return copy(children = children + (name to child.replace(segments, i + 1, old, new, timestamp)))
}
