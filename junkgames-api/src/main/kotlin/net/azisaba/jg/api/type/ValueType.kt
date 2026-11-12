package net.azisaba.jg.api.type

/**
 * Static type of a value (spec §5.5). Checked at compile time; the editor mirrors it with
 * Blockly `check`.
 */
sealed interface ValueType {
    enum class Kind { ANY, NONE, NUMBER, STRING, BOOLEAN, PLAYER, TEAM, ENTITY, LOCATION, REGION, ITEM, MATERIAL, LIST }

    val kind: Kind

    /** Identifier used in IR / editor ("number", "list<player>"). */
    val id: String

    /**
     * Whether a value of type [source] may be connected where this type is expected.
     * Everything converts to string (Scratch-like joining); ANY is accepted both ways and
     * checked again at runtime; a player is also an entity.
     */
    fun accepts(source: ValueType?): Boolean {
        if (source == null || source.kind == Kind.NONE) return false
        if (kind == Kind.ANY || source.kind == Kind.ANY) return true
        if (kind == Kind.STRING) return true
        if (this is ListOf && source is ListOf) return element.accepts(source.element)
        if (kind == Kind.ENTITY && source.kind == Kind.PLAYER) return true
        return kind == source.kind
    }

    @ConsistentCopyVisibility
    data class Simple internal constructor(override val kind: Kind) : ValueType {
        override val id: String get() = kind.name.lowercase()
        override fun toString() = id
    }

    data class ListOf(val element: ValueType) : ValueType {
        override val kind: Kind get() = Kind.LIST
        override val id: String get() = "list<${element.id}>"
        override fun toString() = id
    }

    companion object {
        @JvmField val ANY: ValueType = Simple(Kind.ANY)

        /** "no value" (statements, custom blocks without return). */
        @JvmField val NONE: ValueType = Simple(Kind.NONE)
        @JvmField val NUMBER: ValueType = Simple(Kind.NUMBER)
        @JvmField val STRING: ValueType = Simple(Kind.STRING)
        @JvmField val BOOLEAN: ValueType = Simple(Kind.BOOLEAN)
        @JvmField val PLAYER: ValueType = Simple(Kind.PLAYER)
        @JvmField val TEAM: ValueType = Simple(Kind.TEAM)
        @JvmField val ENTITY: ValueType = Simple(Kind.ENTITY)
        @JvmField val LOCATION: ValueType = Simple(Kind.LOCATION)
        @JvmField val REGION: ValueType = Simple(Kind.REGION)
        @JvmField val ITEM: ValueType = Simple(Kind.ITEM)
        @JvmField val MATERIAL: ValueType = Simple(Kind.MATERIAL)

        @JvmStatic
        fun listOf(element: ValueType): ValueType = ListOf(element)

        @JvmStatic
        fun parse(s: String): ValueType {
            val t = s.trim().lowercase()
            if (t.startsWith("list<") && t.endsWith(">")) return listOf(parse(t.substring(5, t.length - 1)))
            return when (t) {
                "list" -> listOf(ANY)
                "any" -> ANY
                "none", "void", "null" -> NONE
                "number" -> NUMBER
                "string", "text" -> STRING
                "boolean", "bool" -> BOOLEAN
                "player" -> PLAYER
                "team" -> TEAM
                "entity" -> ENTITY
                "location" -> LOCATION
                "region" -> REGION
                "item" -> ITEM
                "material" -> MATERIAL
                else -> throw IllegalArgumentException("unknown type: $s")
            }
        }
    }
}
