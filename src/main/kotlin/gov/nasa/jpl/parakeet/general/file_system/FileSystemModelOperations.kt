package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import gov.nasa.jpl.parakeet.general.file_system.FileSystemNodeOperations.get
import java.nio.file.Path
import kotlin.io.path.name

object FileSystemModelOperations {
    /**
     * Make a directory.
     *
     * Throws an error if [path]'s parent does not exist, or is not a directory.
     * Throws an error if [path] already exists.
     */
    context (_: TaskScope)
    fun FileSystemModel.mkdir(path: Path) {
        val root = getValue()
        val parent = root[path.parent]
        requireNotNull(parent) {
            "Parent directory ${path.parent} does not exist"
        }
        require (parent is Directory) {
            "Parent path ${path.parent} is not a directory"
        }
        require (parent[path.name] == null) {
            "Path $path already exists"
        }
        val newDir = Directory(path.name, now())
        change(path) { newDir }
    }

    context (_: TaskScope)
    fun FileSystemModel.mkdirP(path: Path) {
        val now = now()
        val root = getValue()
        var existingDir = root
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
                change(path.subpath(0, i + 1)) { newDir }
                // Having done that, we're done with this method entirely
                return
            }
        }
        // Note that if we exit the loop without returning explicitly, it's because the directory exists already.
        // In this case, a graceful exit is also the right behavior. Nothing to do here.
    }
}
