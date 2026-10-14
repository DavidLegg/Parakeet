package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteDynamicsMonad
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.resource
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemModel.FileSystemEffect
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.set
import java.nio.file.Path
import kotlin.time.Instant

interface FileSystemModel : DiscreteResource<Directory> {
    context (_: TaskScope)
    fun change(path: Path, effect: FileSystemEffect)

    interface FileSystemEffect : ResourceEffect<Discrete<Directory>> {
        val path: Path
    }
}

// TODO: Should [effect] have a different type here? Maybe just Effect<FSN?>, which we wrap more directly?
fun FileSystemEffect(path: Path, effect: ResourceEffect<Discrete<FileSystemNode?>>, time: Instant) = object : FileSystemEffect {
    override val path: Path = path.normalize()

    override fun invoke(p1: Result<FullDynamics<Discrete<Directory>>>): Result<FullDynamics<Discrete<Directory>>> {
        val initialRootDirectory = p1.getOrThrow().data.value
        val resultAtPath = effect(Result.success(DiscreteDynamicsMonad.pure(initialRootDirectory[path])))
        return resultAtPath.map(DiscreteDynamicsMonad.map { initialRootDirectory.set(path, it, time) })
    }
}

context (scope: InitScope)
fun FileSystemModel(name: String) = object : FileSystemModel {
    private val base: MutableDiscreteResource<Directory> = context(scope) {
        resource(name, Discrete(Directory("", now()))) {
            effects, initialCellValue ->
            // How should this work?
            TODO()
        }
    }

    context (_: TaskScope)
    override fun change(path: Path, effect: ResourceEffect<FileSystemNode>) = base.emit()

    context(scope: ResourceScope)
    override fun getDynamics(): FullDynamics<Discrete<Directory>> = base.getDynamics()
}
