package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffectBatch
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteDynamicsMonad
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.resource
import gov.nasa.jpl.parakeet.foundation.resources.testConcurrentEffects
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModel.FileSystemEffect
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.set
import gov.nasa.jpl.parakeet.kernel.Effect
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.relativeTo
import kotlin.time.Instant

interface FileSystemModel : DiscreteResource<Directory> {
    context (_: TaskScope)
    fun change(path: Path, effect: Effect<FileSystemNode?>)

    data class FileSystemEffect(
        val path: Path,
        val localEffect: Effect<FileSystemNode?>,
        val time: Instant,
    ) : ResourceEffect<Discrete<Directory>> {
        override fun invoke(p1: Result<FullDynamics<Discrete<Directory>>>): Result<FullDynamics<Discrete<Directory>>> {
            val rootDir = p1.getOrThrow().data.value
            return Result.success(DiscreteDynamicsMonad.pure(rootDir.set(path, localEffect(rootDir[path]), time)))
        }

        override fun toString(): String = "$localEffect at $path"
    }
}

/** Build a new [FileSystemEffect], localized at [newRoot] within the current effect's root */
fun FileSystemEffect.at(newRoot: Path): FileSystemEffect = copy(path = path.relativeTo(newRoot))
/** Get this as a direct effect on a [Directory], instead of a resource effect. */
val FileSystemEffect.directEffect: Effect<FileSystemNode?> get() = {
    if (path.nameCount == 0) {
        localEffect(it)
    } else {
        // TODO: Copy some nicer error-checking in here, rather than a straight cast
        it as Directory
        it.set(path, localEffect(it[path]), time)
    }
}

context (scope: InitScope)
fun FileSystemModel(name: String, initialDirectory: Directory = Directory("", now())): FileSystemModel {
    require(initialDirectory.name.isEmpty()) {
        "root directory for a ${FileSystemModel::class.simpleName} must have empty string as its name"
    }
    return object : FileSystemModel {
        private val base: MutableDiscreteResource<Directory> = context(scope) {
            resource(name, Discrete(initialDirectory), ::resolveFileSystemEffects)
        }

        context(_: TaskScope)
        override fun change(path: Path, effect: Effect<FileSystemNode?>) =
            base.emit(FileSystemEffect(path.normalize(), effect, now()))

        context(scope: ResourceScope)
        override fun getDynamics(): FullDynamics<Discrete<Directory>> = base.getDynamics()
    }
}

/**
 * Resolve a batch of file system effects.
 *
 * We assume that a file system may be a high-traffic resource.
 * To avoid flooding the automatic concurrency resolver, this method splits file system effects based on the paths they're acting on.
 * It then explicitly permutation-tests only those effects which concurrently act on comparable paths.
 *
 * Put another way, concurrent writes to sibling file system nodes (or descendants of sibling nodes) are known to commute.
 * This function detects this case, and doesn't explicitly test this commutativity.
 * In a busy file system, this drastically cuts down on the amount of work spent checking file commutativity.
 *
 * Despite the signature, assumes that all effects are [FileSystemEffect]s.
 */
private fun resolveFileSystemEffects(
    effectBatch: ResourceEffectBatch<Discrete<Directory>>,
    initialCellValue: Result<FullDynamics<Discrete<Directory>>>,
): Result<FullDynamics<Discrete<Directory>>> {
    @Suppress("UNCHECKED_CAST")
    effectBatch as List<List<FileSystemEffect>>

    // Group effects within each branch into "branchlets", noting their root path
    val branchlets: List<Pair<Path, List<FileSystemEffect>>> =
        effectBatch.flatMap { groupByRootPath(it) { it.path } }
    // Then, group branchlets together into branch-groups
    val branchGroups: List<Pair<Path, List<List<FileSystemEffect>>>> =
        groupByRootPath(branchlets, { it.first }) { it.second }

    var workingResult: Directory = initialCellValue.getOrThrow().data.value

    // We only need to test permutations within each branch-group; all branch-groups commute with each other.
    for ((groupPath, branchGroup) in branchGroups) {
        val time = branchGroup.first().first().time
        // To reduce work during commutativity checks, each branchGroup is applied to its root directory at groupPath.
        // This avoids recomputing parent directories of groupPath for each permutation.
        val initialLocalResult = workingResult[groupPath]
        try {
            val finalLocalResult = if (branchGroup.size == 1) {
                // Special case: no commutativity to check, just apply everything directly
                branchGroup.single().fold(initialLocalResult) { x, f -> f.directEffect(x) }
            } else {
                // General case: Localize effects to groupPath, then build and collect direct effects to run
                testConcurrentEffects(
                    branchGroup.map { branchlet -> branchlet.map { it.at(groupPath).directEffect } },
                    initialLocalResult
                )
            }
            // Regardless of how we applied this branchGroup, if it worked, set the final result in workingResult
            workingResult = workingResult.set(groupPath, finalLocalResult, time)
        } catch (e: Throwable) {
            // TODO: Make e a little more specific than just "Throwable", maybe?
            // If applying this branchGroup failed, build a corrupted node to indicate that failure
            val corruptedNode = if (initialLocalResult == null) {
                Corrupted(groupPath.name, time, exception = e)
            } else {
                Corrupted(initialLocalResult, exception = e)
            }
            workingResult = workingResult.set(groupPath, corruptedNode, time)
        }
    }

    return Result.success(DiscreteDynamicsMonad.pure(workingResult))
}

/** Group things into classes with a common root path. */
private fun <T> groupByRootPath(things: List<T>, getPath: (T) -> Path): List<Pair<Path, List<T>>> =
    groupByRootPath(things, getPath) { it }

/** Group things into classes with a common root path. To reduce copying, a transform is applied during grouping. */
private fun <T, R> groupByRootPath(
    things: List<T>,
    getPath: (T) -> Path,
    transform: (T) -> R,
): List<Pair<Path, List<R>>> {
    val result = mutableListOf<Pair<Path, MutableList<R>>>()
    for (thing in things) {
        val rThing = transform(thing)
        val path = getPath(thing)
        var foundExistingGroup = false
        var newGroup: MutableList<R>? = null
        val iter = result.iterator()
        while (iter.hasNext()) {
            val (root, group) = iter.next()
            if (newGroup == null) {
                // We haven't found a group to add this thing to
                if (path.startsWith(root)) {
                    // thing belongs to, and does not change, this group
                    group.add(rThing)
                    foundExistingGroup = true
                    break
                } else if (root.startsWith(path)) {
                    // thing is the new root for group, and may collect other groups as it goes
                    newGroup = group
                    iter.remove()
                }
            } else {
                // thing is augmenting a group, so only one direction of the path containment is needed
                if (root.startsWith(path)) {
                    // Absorb group into newGroup, since thing is a common root for both
                    newGroup += group
                    iter.remove()
                }
            }
        }
        if (newGroup != null) {
            result += path to newGroup
        } else if (!foundExistingGroup) {
            result += path to mutableListOf(rThing)
        }
        // else: we found an existing group to belong to, nothing to do
    }
    return result
}
