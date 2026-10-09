package gov.nasa.jpl.parakeet.general.file_system

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.time.Instant

/**
 * A node in the modeled file system: either a [File] or a [Directory].
 *
 * Nodes are immutable. Equality is determined by a cached SHA-256 digest of the node's content,
 * so comparing two nodes (even large directory trees) is cheap.
 */
@Serializable
sealed interface FileNode {
    val createdTime: Instant
    val modifiedTime: Instant

    // TODO: Digest should be private
    /** SHA-256 digest of this node and all its descendants. Used only for equality and hashing. */
    val digest: ByteArray
}


// TODO: Be a little more efficient when copying files - maybe make a copy constructor that bypasses copying bytes
/**
 * Opaque, immutable bytes for a [File]'s content.
 * Defensively copies on the way in and out, and compares by content.
 */
@Serializable
class FileContent private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size
    fun toByteArray(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?) = other is FileContent && bytes.contentEquals(other.bytes)
    override fun hashCode() = bytes.contentHashCode()
    override fun toString() = "FileContent($size bytes)"

    companion object {
        fun of(bytes: ByteArray) = FileContent(bytes.copyOf())
        fun of(text: String) = FileContent(text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * A modeled file.
 *
 * [content] is optional, since many modeled files shouldn't have their contents modeled.
 * [size] is tracked independently of [content], and need not match it.
 */
@Serializable
class File(
    override val createdTime: Instant,
    override val modifiedTime: Instant,
    val size: Long = 0,
    val content: FileContent? = null,
) : FileNode {
    @Transient
    override val digest: ByteArray = sha256 {
        update(FILE_TAG)
        update(createdTime)
        update(modifiedTime)
        update(size)
        // We can just update with the content if it's non-null
        content?.let { update(1); update(it.toByteArray()) } ?: update(0)
    }

    // TODO: Use a record instead of a copy constructor
    fun copy(
        createdTime: Instant = this.createdTime,
        modifiedTime: Instant = this.modifiedTime,
        size: Long = this.size,
        content: FileContent? = this.content,
    ) = File(createdTime, modifiedTime, size, content)

    override fun equals(other: Any?) = other is File && digest.contentEquals(other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "File(created=$createdTime, modified=$modifiedTime, size=$size, content=$content)"
}

/**
 * A modeled directory, mapping name segments to child nodes.
 *
 * Aggregates ([totalSize], [fileCount], and the equality digest) are computed at construction,
 * so updating a tree only recomputes the nodes along the changed path.
 */
@Serializable
class Directory(
    override val createdTime: Instant,
    override val modifiedTime: Instant,
    val children: Map<String, FileNode> = emptyMap(),
) : FileNode {
    /** Sum of the sizes of all descendant files. Directories themselves contribute 0. */
    @Transient
    val totalSize: Long = children.values.sumOf { if (it is File) it.size else (it as Directory).totalSize }

    /** Number of descendant files. */
    @Transient
    val fileCount: Long = children.values.sumOf { if (it is File) 1L else (it as Directory).fileCount }

    @Transient
    override val digest: ByteArray = sha256 {
        update(DIRECTORY_TAG)
        update(createdTime)
        update(modifiedTime)
        update(children.size.toLong())
        // Sort by name so equal maps with different iteration order produce equal digests.
        for ((name, child) in children.entries.sortedBy { it.key }) {
            update(name.toByteArray(Charsets.UTF_8).size.toLong())
            update(name.toByteArray(Charsets.UTF_8))
            update(child.digest)
        }
    }

    fun copy(
        createdTime: Instant = this.createdTime,
        modifiedTime: Instant = this.modifiedTime,
        children: Map<String, FileNode> = this.children,
    ) = Directory(createdTime, modifiedTime, children)

    override fun equals(other: Any?) = other is Directory && digest.contentEquals(other.digest)
    override fun hashCode() = digest.contentHashCode()
    override fun toString() = "Directory(created=$createdTime, modified=$modifiedTime, children=${children.keys})"
}

private const val FILE_TAG: Byte = 'F'.code.toByte()
private const val DIRECTORY_TAG: Byte = 'D'.code.toByte()

// TODO: Re-write these methods as extensions on MessageDigest (if there aren't extensions already) which shift the bytes in without allocating byte arrays
private class DigestBuilder(private val md: MessageDigest) {
    fun update(b: Byte) = md.update(b)
    // TODO: (BUG) truncates the int to a byte
    fun update(b: Int) = md.update(b.toByte())
    fun update(bytes: ByteArray) = md.update(bytes)
    // TODO: Could be a little more efficient here
    fun update(n: Long) = md.update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(n).array())
    fun update(t: Instant) { update(t.epochSeconds); update(t.nanosecondsOfSecond) }
}

private fun sha256(block: DigestBuilder.() -> Unit): ByteArray =
    MessageDigest.getInstance("SHA-256").also { DigestBuilder(it).block() }.digest()
