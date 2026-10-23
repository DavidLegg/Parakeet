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
import gov.nasa.jpl.parakeet.kernel.EffectBranch
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
            effectBatch, initialCellValue ->
            var workingResult = initialCellValue.getOrThrow().data.value

            @Suppress("UNCHECKED_CAST")
            effectBatch as List<List<FileSystemEffect>>

            // TODO: We need to rework everything below.
            //   This was figured out on the mistaken belief that we permute effects.
            //   We don't. We permute branches.
            //   The key distinction is that a branch affects many files (in general), not a single file.
            //   This complicates the grouping logic below.
            //   We could extend the grouping logic to say that each branch affects the LCA, but this is likely to be too broad, I think.
            //   Stated vaguely, I think we need to group all the paths we'll affect into classes, each represented by the root path.
            //   A path is in that class if it's within that root path, and the classes are as atomic as they can be while having incomparable roots.
            //   Then, we need to do a similar grouping across branches, such that each branch-class acts on incomparable path-classes.
            //   Finally, for each branch-class, test all permutations of branches.
            //   To test a branch permutation, check every root path affected by that branch-class.
            //   Any root path which is ambiguous is corrupted, but other root paths (even in a failed branch permutation)
            //   can be resolved to a legitimate value.

            // TODO: see if allPaths can be eliminated in favor of double-for-loop, to avoid intermediate copying
            fun List<FileSystemEffect>.affectedPaths() = map { it.path }
            val allPaths: List<Path> = effectBatch.flatMap { it.affectedPaths() }
            // TODO: pathClasses should actually be a union-find data structure, so we can efficiently query path-congruence
            val pathClasses: MutableMap<Path, MutableList<Path>> = mutableMapOf()
            for (path in allPaths) {
                // Start by assuming we'll need to build a new path class.
                var newPathClass: MutableList<Path>? = mutableListOf(path)
                val iter = pathClasses.iterator()
                while (iter.hasNext()) {
                    val (rootPath, pathClass) = iter.next()
                    if (path.startsWith(rootPath)) {
                        // path belongs to pathClass, not a new path class
                        pathClass.add(path)
                        newPathClass = null
                        // Since all other path classes are incomparable to rootPath, they are incomparable to path, so we're done.
                        break
                    } else if (rootPath.startsWith(path)) {
                        // path extends pathClass, so remove pathClass to replace it with path
                        iter.remove()
                        newPathClass!!.addAll(pathClass)
                    }
                }
                // If we still want to build a new path class, do so.
                newPathClass?.let { pathClasses[path] = it }
            }
            // At this point, pathClasses' keys are incomparable root paths, such that all paths belong to exactly one class.
            // First, let's invert pathClass to get a map from each path to its root path:
            val pathToRootPath = mutableMapOf<Path, Path>()
            for ((rootPath, pathClass) in pathClasses) {
                pathClass.associateWithTo(pathToRootPath) { rootPath }
            }
            // Finally, we need to build congruence classes of branches based on intersecting root path sets

            /** Associate a set of branches and the set of root paths those branches affect. */
            class BranchClass(branch: List<FileSystemEffect>) {
                val branches: MutableList<List<FileSystemEffect>> = mutableListOf(branch)
                // Start out by associating each branch to the set of root paths it touches
                val rootPaths: MutableSet<Path> = branch.affectedPaths().mapTo(mutableSetOf(), pathToRootPath::getValue)

                /** Merge [other] into this */
                operator fun plusAssign(other: BranchClass) {
                    branches += other.branches
                    rootPaths += other.rootPaths
                }

                /** Test if this and [other] overlap in what root paths they affect */
                infix fun interactsWith(other: BranchClass): Boolean =
                    other.rootPaths.any { it in rootPaths }
            }

            val branchClasses = mutableListOf<BranchClass>()
            for (branch in effectBatch) {
                val branchClass = BranchClass(branch)
                val mergeClasses = mutableListOf<BranchClass>()
                val iter = branchClasses.iterator()
                while (iter.hasNext()) {
                    val existingBranchClass = iter.next()
                    if (branchClass interactsWith existingBranchClass) {
                        // Delete that existing branch class
                        iter.remove()
                        // Track that we'll need to merge this class in later
                        mergeClasses += existingBranchClass
                        // Note that we don't merge immediately, as that'll make it more expensive to check interactions later.
                        // We know that existingBranchClass doesn't interact with later branch classes, so we don't need that check for correctness.
                    }
                }
                // Merge everything into branchClass
                mergeClasses.forEach { branchClass += it }
                // And finally add branchClass as its own congruence class
                branchClasses += branchClass
            }

            // At this point, we have congruence classes of branches, and the set of root paths they affect.
            // From there, we should be able to permute each branch class independently and perform targeted
            // corruption detection and resolution on the root paths for that branch class.

            TODO()
        }
    }

    context (_: TaskScope)
    override fun change(path: Path, effect: FileSystemEffect) = TODO()

    context(scope: ResourceScope)
    override fun getDynamics(): FullDynamics<Discrete<Directory>> = base.getDynamics()
}
