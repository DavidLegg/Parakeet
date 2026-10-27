package gov.nasa.jpl.parakeet.general.file_system

import gov.nasa.jpl.parakeet.general.file_system.FileSystemNode.*
import java.nio.file.Path
import kotlin.io.path.div
import kotlin.io.path.name
import kotlin.time.Instant

object FileSystemNodeOperations {
    /**
     * Get the child of this directory named by [name], or null if no such child exists.
     */
    operator fun Directory.get(name: String): FileSystemNode? = children[name]

    /**
     * Get the node of this directory indicated by local-relative [path] inside [this], or null if no such node exists.
     */
    operator fun Directory.get(path: Path): FileSystemNode? {
        var result: FileSystemNode? = this
        for (name in path.localRelativePath()) {
            result = (result as? Directory)?.get(name.name)
        }
        return result
    }

    /**
     * Return all descendants matching the glob [pattern].
     *
     * Supported glob elements:
     * - &ast;&ast; to recursively match all descendant nodes
     * - any \-escaped character (interpreted literally)
     * - ? to match any single character
     * - &ast; to match any sequence of characters
     * - \[abc\] to match any of the included characters
     * - \[^abc\] to match any character except the included characters
     * - \[a-c\] to match any character in the range
     * - \[^a-c\] to match any character not in the range
     *
     * Note that &ast;&ast; must be the only thing in that component of the pattern.
     * So, 'foo/&ast;&ast;/bar' is valid, but 'foo/&ast;&ast;bar' isn't.
     * All other glob elements may be combined within a component, and apply only within that component of a matching path.
     */
    fun Directory.glob(pattern: String): Map<Path, FileSystemNode> {
        // To start, interpret the special components of the path literally
        // You ought not to put a path separator inside a special pattern like [] groups, so this should be good enough.
        // TODO: Think through what happens if someone disobeys this rule anyways
        val patternPath = Path.of(pattern)
        // Performing breadth-first search with a map ensures that '**' expansions don't duplicate results
        // If we find the same node twice, it'll have the same expanded path, so we'll just write it into the map twice (a no-op).
        var frontier: Map<Path, FileSystemNode> = mapOf(Path.of("/") to this)
        for (component in patternPath) {
            val c = component.name
            val newFrontier = mutableMapOf<Path, FileSystemNode>()
            when {
                c == "**" -> {
                    val worklist = ArrayDeque(frontier.entries.map { (k, v) -> k to v })
                    while (worklist.isNotEmpty()) {
                        val (path, node) = worklist.removeFirst()
                        // If we've already explored this path, don't duplicate effort
                        if (path in newFrontier) continue
                        // ** can match 0 directories, so any node we've already found is fair game
                        newFrontier[path] = node
                        // If this found node is a directory, add all its children to be processed too
                        if (node is Directory) {
                            for ((childName, child) in node.children) {
                                worklist.add((path / childName) to child)
                            }
                        }
                    }
                }
                "*" in c || "?" in c -> {
                    val cRegex = Regex(buildString {
                        var escaped = false
                        for (cChar in c) {
                            if (escaped) {
                                append(Regex.fromLiteral(cChar.toString()))
                                escaped = false
                            }
                            when (cChar) {
                                '\\' -> escaped = true
                                '?' -> append('.')
                                '*' -> append(".*")
                                else -> append(Regex.fromLiteral(cChar.toString()))
                            }
                        }
                    })
                    for ((path, node) in frontier) {
                        if (node is Directory) {
                            for ((childName, child) in node.children) {
                                if (childName.matches(cRegex)) {
                                    newFrontier[path / childName] = child
                                }
                            }
                        }
                    }
                }
                else -> {
                    for ((path, node) in frontier) {
                        if (node is Directory) {
                            val child = node[c]
                            if (child != null) {
                                newFrontier[path / c] = child
                            }
                        }
                    }
                }
            }
            frontier = newFrontier
        }
        return frontier
    }

    /**
     * Check if [this] contains a child named [name].
     */
    operator fun Directory.contains(name: String): Boolean = name in children

    /**
     * Check if local-relative [path] inside [this] exists.
     */
    operator fun Directory.contains(path: Path): Boolean = this[path] != null

    /**
     * True if this directory contains no child nodes.
     *
     * Note that an empty directory may not have size 0 bytes.
     */
    fun Directory.isEmpty(): Boolean = children.isEmpty()

    /**
     * True if this directory contains any child nodes.
     *
     * Note that an empty directory may not have size 0 bytes.
     */
    fun Directory.isNotEmpty(): Boolean = children.isNotEmpty()

    /*
     * "Mutation" functions
     *
     * Since FileSystemNodes are immutable by design, mutations must rebuild parts of the file system.
     * In particular, there are digests that must be recomputed along the path of change.
     *
     * The utilities below perform these changes as efficiently as we reasonably can.
     */

    /**
     * Return a copy of [this] where the child node named [name] is:
     * - set to [value], if [value] is non-null, or
     * - removed, if [value] is null
     *
     * Other fields of the result are updated for consistency.
     */
    fun Directory.set(name: String, value: FileSystemNode?, time: Instant): Directory = this.copy(
        children = children.toMutableMap().apply {
            compute(name) { _, _ -> value }
        },
        // Update modifiedTime if we are creating or deleting a node
        // TODO: Consider refining this to also update the modified time if we change the type of the child?
        modifiedTime = if ((name in children) == (value != null)) modifiedTime else time
    )

    /**
     * Return a copy of [this] where the descendant node at local-relative [path] is:
     * - set to [value], if [value] is non-null, or
     * - removed, if [value] is null
     *
     * Other fields of the result are updated for consistency,
     * along with all intermediate [Directory] nodes along [path].
     */
    fun Directory.set(path: Path, value: FileSystemNode?, time: Instant): Directory {
        val normalPath = path.localRelativePath()
        return if (normalPath.nameCount == 0) {
            requireNotNull(value) {
                "Cannot delete root directory"
            }
            require(value is Directory) {
                "Cannot set root directory to a non-directory file node"
            }
            value
        } else {
            val intermediateDirs = mutableListOf<Directory>()
            var finalDir = this
            val pathToFinalDir = normalPath.parent
            if (pathToFinalDir != null) {
                for (component in pathToFinalDir) {
                    val nextDir = finalDir[component]
                    requireNotNull(nextDir) {
                        "Intermediate directory ${component.name} not found for $normalPath"
                    }
                    require(nextDir is Directory) {
                        "Cannot descend into non-directory node ${component.name} in $normalPath"
                    }
                    intermediateDirs += finalDir
                    finalDir = nextDir
                }
            }
            // At this point, intermediateDirs holds the list of all directories we descended to get to finalDir
            // and finalDir holds the final directory we need to change.

            // Now we'll make the change in finalDir:
            finalDir = finalDir.set(normalPath.name, value, time)

            // And finally we'll roll the changes up the path of intermediate dirs
            for (dir in intermediateDirs.asReversed()) {
                finalDir = dir.set(finalDir.name, finalDir, time)
            }

            // At this point, finalDir holds the top-level dir, changed along all of path
            finalDir
        }
    }

    /**
     * Return a copy of [this] where the child node named [name] is removed.
     *
     * Other fields of the result are updated for consistency.
     */
    fun Directory.remove(name: String, time: Instant) = set(name, null, time)

    /**
     * Return a copy of [this] where the descendant node at local-relative [path] is removed.
     *
     * Other fields of the result are updated for consistency,
     * along with all intermediate [Directory] nodes along [path].
     */
    fun Directory.remove(path: Path, time: Instant) = set(path, null, time)

    /** Whether this path is a relative path within the working directory (not a parent or sibling). */
    private fun Path.localRelativePath(): Path {
        require(!isAbsolute) {
            "Absolute path $this cannot be used as a local relative path."
        }
        val normalPath = normalize()
        // Normalizing removes most of the ways a path can be invalid.
        // The only remaining invalid case is a relative path to a parent or sibling directory.
        // These paths start by traversing "up" out of the indicated directory, which we can't support.
        require (!normalPath.startsWith("..")) {
            "Relative path $this indicates a location outside the current directory, so is not a local relative path."
        }
        // At this point, we've verified that normalPath contains only named-child path components.
        return normalPath
    }
}

/**
 * Build a regex which matches the same strings as (single-level) glob [pattern].
 *
 * Supported glob elements:
 * - any \-escaped character (interpreted literally)
 * - ? to match any single character
 * - &ast; to match any sequence of characters
 * - \[abc\] to match any of the included characters
 * - \[^abc\] to match any character except the included characters
 * - \[a-c\] to match any character in the range
 * - \[^a-c\] to match any character not in the range
 */
private fun globToRegex(pattern: String): Regex = Regex(buildString {
    var i = 0
    while (i < pattern.length) {
        val c = pattern[i]
        when (c) {
            '\\' -> {
                // Skip the escape character and append the next char as a literal.
                i++
                append(Regex.fromLiteral(pattern[i].toString()))
            }
            '?' -> append('.')
            '*' -> append(".*")
            '[' -> {
                // Advance past the [, and build the character group as indicated
                val charGroupStart = i
                i++
                while (true) {
                    val d = pattern[i]
                    // Within the [] group, only ] and \ (escape) have special meaning
                    when (d) {
                        '\\' -> i++
                        ']' -> break
                    }
                    append(pattern[i])
                    i++
                }
                // At this point, i points to the closing square bracket.
                // Regex and glob coincide in syntax for [] character groups, so just inline it
                append(pattern.substring(charGroupStart..i))
            }
            '{' -> TODO("Alternations are not yet supported")
            else -> append(Regex.fromLiteral(c.toString()))
        }
        i++
    }
})
