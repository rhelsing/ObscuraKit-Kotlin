package com.obscura.kit.orm

/**
 * Configuration for a model definition.
 * Maps to the JS schema config object.
 */
data class ModelConfig(
    val fields: Map<String, String> = emptyMap(), // field name -> type ("string", "number?", etc.)
    val sync: String = "gset",                    // "gset" or "lww"
    val private: Boolean = false,                 // true = only sync to own devices
    val ttl: String? = null,                      // e.g., "24h", "7d"
    val belongsTo: List<String> = emptyList(),    // parent model names
    val hasMany: List<String> = emptyList(),      // child model names
    val direct: Boolean = false                   // true = 1:1 payload; MUST resolve an explicit
                                                   // recipient or fail loud — never broadcasts
) {
    val isPrivate: Boolean get() = `private`
}
