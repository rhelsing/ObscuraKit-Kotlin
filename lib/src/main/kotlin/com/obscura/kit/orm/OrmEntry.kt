package com.obscura.kit.orm

/**
 * Universal entry type for all ORM models.
 * Both GSet and LWWMap entries use the same shape.
 */
data class OrmEntry(
    val id: String,
    val data: Map<String, Any?>,
    val timestamp: Long,
    val authorDeviceId: String,
    val signature: ByteArray = ByteArray(0)
) {
    val isDeleted: Boolean
        get() = data["_deleted"] == true

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OrmEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
