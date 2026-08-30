package com.airclip.core.clipboard

enum class ClipKind { TEXT, IMAGE }

/**
 * PNG bytes for transport; [pixelHash] is the platform-neutral content hash over the canonical
 * BGRA form, which is what peers actually compare.
 */
class ClipImage(
    val width: Int,
    val height: Int,
    val png: ByteArray,
    val pixelHash: String,
) {
    // Identity is the content hash, not the encoded bytes: two encoders can disagree byte-for-byte
    // about the same picture, and a data class would compare ByteArray by reference anyway.
    override fun equals(other: Any?): Boolean =
        other is ClipImage && other.pixelHash == pixelHash && other.width == width && other.height == height

    override fun hashCode(): Int = pixelHash.hashCode()
}

/** One clipboard item, already normalised and hashed. Immutable; safe to hand across threads. */
class ClipContent private constructor(
    val kind: ClipKind,
    val text: String?,
    val image: ClipImage?,
    val hash: String,
) {
    val byteSize: Int
        get() = if (kind == ClipKind.TEXT) text!!.toByteArray(Charsets.UTF_8).size else image!!.png.size

    override fun toString(): String = if (kind == ClipKind.TEXT) {
        "text ${byteSize}B #${hash.take(8)}"
    } else {
        "image ${image!!.width}x${image.height} ${byteSize}B #${hash.take(8)}"
    }

    companion object {
        fun fromText(text: String): ClipContent =
            ClipContent(ClipKind.TEXT, text, null, ContentHasher.hashText(text))

        fun fromImage(image: ClipImage): ClipContent =
            ClipContent(ClipKind.IMAGE, null, image, image.pixelHash)
    }
}
