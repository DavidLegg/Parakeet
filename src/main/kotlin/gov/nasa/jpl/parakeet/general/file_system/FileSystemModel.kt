package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteDynamicsMonad
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.resource
import gov.nasa.jpl.parakeet.foundation.resources.safelyApplyConcurrentEffects
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
import kotlin.time.Instant

interface FileSystemModel : DiscreteResource<Directory> {
    context (_: TaskScope)
    fun change(path: Path, effect: FileSystemEffect)

    interface FileSystemEffect : ResourceEffect<Discrete<Directory>> {
        val path: Path
        val localEffect: Effect<FileSystemNode?>
        val time: Instant
    }
}

fun FileSystemEffect(path: Path, effect: Effect<FileSystemNode?>, time: Instant) = object : FileSystemEffect {
    override val path: Path = path.normalize()
    override val localEffect: Effect<FileSystemNode?> = effect
    override val time: Instant = time

    override fun invoke(p1: Result<FullDynamics<Discrete<Directory>>>): Result<FullDynamics<Discrete<Directory>>> {
        val initialRootDirectory = p1.getOrThrow().data.value
        val resultAtPath = effect(initialRootDirectory[path])
        return Result.success(DiscreteDynamicsMonad.pure(initialRootDirectory.set(path, resultAtPath, time)))
    }
}

context (scope: InitScope)
fun FileSystemModel(name: String) = object : FileSystemModel {
    private val base: MutableDiscreteResource<Directory> = context(scope) {
        resource(name, Discrete(Directory("", now()))) {
            effects, initialCellValue ->
            var workingResult = initialCellValue.getOrThrow().data.value

            // How should this work? We need to merge all the effects...
            // Case 1: f and g act on mutually exclusive paths - apply both
            // Case 2: f and g act on the same path - safely apply both, resulting in a conflicted node if that faults
            // Case 3: f acts on a prefix of g's path - safely apply both, resulting in a conflicted node at f's path if that faults
            // However, this two-case logic needs to extend to all concurrent effects simultaneously...

            // After a little bit of thought, we can reduce the first part of this problem (deciding which permutations to check)
            // down to "check permutations modulo incomparable paths", using the prefix partial ordering on paths.
            // For example, if paths are /foo, /foo/bar, and /foo/baz, then only the following orders matter:
            // 123, 213, 312, 231 - 132 is (23)123, 321 is (23)231.
            // Since paths 2 and 3 are incomparable, their relative order in the permutation is irrelevant.

            // Computing this exactly is hard, but there's a slightly less precise simplification we can make.
            // Partition the paths by the closure of comparability. In the example above, all three paths go into the same class.
            // Then, each class will have a root directory, all of them local-relative to that dir.
            // This root dir is a path in that class. Each class has a root dir incomparable to the rest.
            // Hence, it's conservative to try every permutation of effects in a class on that class' root dir,
            // but to act on each class independently.

            // TODO: All of this is bunk. When merging sequential effects, we build a generic ResourceEffect, not a FSE.
            //   So, the casts below fail in general.

            val pathClasses = mutableMapOf<Path, MutableList<FileSystemEffect>>()
            for (effect in effects) {
                effect as FileSystemEffect
                var foundExistingClass = false
                var enlargedClass: MutableList<FileSystemEffect>? = null
                val iter = pathClasses.iterator()
                while (iter.hasNext()) {
                    val (path, pathClass) = iter.next()
                    if (enlargedClass == null) {
                        if (effect.path.startsWith(path)) {
                            // Effect belongs to this class
                            pathClass += effect
                            foundExistingClass = true
                            // Since path is incomparable to all other class rep's, this class doesn't merge with another
                            break
                        } else if (path.startsWith(effect.path)) {
                            // Effect belongs to this class
                            pathClass += effect
                            // But we've also moved the root path up to effect.path.
                            // Remove that pathClass we found, in preparation to add it back under effect.path
                            iter.remove()
                            // We've already checked the classes before this one, and they were incomparable to effect.path
                            // Continue in a special mode, where we'll do a one-sided check to add to our newly-enlarged class.
                            enlargedClass = pathClass
                        }
                    } else {
                        if (path.startsWith(effect.path)) {
                            // We need to merge this class with the class of effect.path
                            // Merge its elements into those for the new class:
                            // TODO: Make this a union-find data structure instead of map of lists, to avoid this copy?
                            enlargedClass += pathClass
                            // Then remove this class from the map
                            iter.remove()
                        }
                    }
                }
                if (!foundExistingClass) {
                    pathClasses[effect.path] = enlargedClass ?: mutableListOf(effect)
                }
            }

            // At this point, pathClasses contains mutually-incomparable effects.
            // Each group must be safely-merged by testing all permutations.
            // But, each group is independent, and we may sequentially process all groups.

            for ((path, pathClass) in pathClasses) {
                val classTime = pathClass.first().time
                if (pathClass.size == 1) {
                    // Special case - no commutativity to check!
                    val effect = pathClass.single()
                    workingResult = workingResult.set(path, effect.localEffect(workingResult[path]), classTime)
                } else {
                    // General case - there are multiple permutations to try!
                    // Extract the root that this group acts on
                    val localInitialValue = workingResult[path]
                    // TODO: There's a bug here, we need to reframe the effects below as local effects on the group root node.
                    val localResult: FileSystemNode? = try {
                        testConcurrentEffects(
                            pathClass.map { it.localEffect },
                            localInitialValue
                        )
                    } catch (e: Throwable) {
                        // Something failed, probably a concurrency problem!
                        if (localInitialValue != null) {
                            // If we had an original file, we corrupted it
                            Corrupted(localInitialValue, exception = e)
                        } else {
                            // Otherwise, we created a corrupted file brand-new
                            Corrupted(path.name, classTime, exception = e)
                        }
                    }
                    workingResult.set(path, localResult, classTime)
                }
            }

            TODO()
        }
    }

    context (_: TaskScope)
    override fun change(path: Path, effect: ResourceEffect<FileSystemNode>) = base.emit()

    context(scope: ResourceScope)
    override fun getDynamics(): FullDynamics<Discrete<Directory>> = base.getDynamics()
}
