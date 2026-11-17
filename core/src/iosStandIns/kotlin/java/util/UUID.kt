package java.util

import platform.Foundation.NSUUID

/**
 * java.util.UUID, the part TMPlayer uses: a random (version 4) UUID and its text, from Foundation's
 * NSUUID, lower case as the JDK writes it. Added when the iOS port was brought into TMPlayer
 * (docs/IOS.md): the search session's id came after the port's base.
 */
class UUID private constructor(private val text: String) : Comparable<UUID> {
    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is UUID && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun compareTo(other: UUID): Int = text.compareTo(other.text)

    companion object {
        fun randomUUID(): UUID = UUID(NSUUID().UUIDString.lowercase())
    }
}
