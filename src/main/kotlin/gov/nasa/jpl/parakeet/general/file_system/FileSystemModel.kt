package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.DynamicsMonad
import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteDynamicsMonad
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModel.FileSystemEffect
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import java.nio.file.Path

interface FileSystemModel : DiscreteResource<Directory> {
    context (_: TaskScope)
    fun change(path: Path, effect: FileSystemEffect)

    interface FileSystemEffect : ResourceEffect<Discrete<Directory>> {
        val path: Path
    }
}

fun FileSystemEffect(path: Path, effect: ResourceEffect<Discrete<FileSystemNode?>>) = object : FileSystemEffect {
    override val path: Path = path.normalize()

    override fun invoke(p1: Result<FullDynamics<Discrete<Directory>>>): Result<FullDynamics<Discrete<Directory>>> {
        val initialRootDirectory = p1.getOrThrow().data.value
        val resultAtPath = effect(Result.success(DiscreteDynamicsMonad.pure(initialRootDirectory[path])))
        // TODO: Rebuild initialRootDirectory down to resultAtPath...
    }
}

context (scope: InitScope)
fun FileSystemModel(name: String) = object : FileSystemModel {
    private val base: MutableDiscreteResource<Directory> = context(scope) {
        discreteResource(name, Directory("", now(), now(), emptyMap()))
    }

    context (_: TaskScope)
    override fun change(path: Path, effect: ResourceEffect<FileSystemNode>) = base.emit()

    context(scope: ResourceScope)
    override fun getDynamics(): FullDynamics<Discrete<Directory>> = base.getDynamics()
}
