package net.azisaba.jg.core.block

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import net.azisaba.jg.api.block.BlockDefinition
import net.azisaba.jg.api.block.BlockShape
import net.azisaba.jg.api.block.FieldSpec
import net.azisaba.jg.core.provider.CapabilityRegistry

/**
 * Generates the editor's block definitions from the registry (spec §6.3: never hand-written).
 * The output is what `GET /api/blocks?server=<id>` serves and what the server sends in its
 * `hello` (spec §10.2). Blocks whose capability requirements are not met here are marked
 * `available: false` with reasons, so the editor can grey them out (spec §12.4).
 */
object BlockExporter {
    private val PLACEHOLDER = Regex("%([a-zA-Z0-9_]+)")

    fun export(serverId: String, coreVersion: String, blocks: Collection<BlockDefinition>, caps: CapabilityRegistry): String {
        val root = JsonObject()
        root.addProperty("format", "junkgames/blocks")
        root.addProperty("server", serverId)
        root.addProperty("core", coreVersion)
        root.add("capabilities", caps.describe())
        val categories = LinkedHashMap<String, JsonObject>()
        val arr = JsonArray()
        for (d in blocks) {
            categories.getOrPut(d.category.id) {
                JsonObject().apply {
                    addProperty("id", d.category.id)
                    addProperty("color", d.category.color)
                    add("name", JsonObject().apply { d.category.label.translations.forEach { (k, v) -> addProperty(k, v) } })
                }
            }
            arr.add(block(d, caps))
        }
        root.add("categories", JsonArray().apply { categories.values.forEach { add(it) } })
        root.add("blocks", arr)
        return GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root)
    }

    private fun block(d: BlockDefinition, caps: CapabilityRegistry) = JsonObject().apply {
        addProperty("type", d.id)
        addProperty("v", d.schemaVersion)
        addProperty("shape", d.shape.name.lowercase())
        addProperty("category", d.category.id)
        addProperty("owner", d.owner)
        d.output?.let { addProperty("output", it.id) }
        add("label", JsonObject().apply { d.label.translations.forEach { (k, v) -> addProperty(k, v) } })
        add("inputs", JsonArray().apply {
            d.inputs.forEach { i ->
                add(JsonObject().apply {
                    addProperty("name", i.name)
                    addProperty("type", i.type.id)
                    i.defaultLiteral?.let { add("default", it) }
                    i.defaultBlock?.let { addProperty("defaultBlock", it) }
                    if (i.optional) addProperty("optional", true)
                })
            }
        })
        add("fields", JsonArray().apply {
            d.fields.forEach { f ->
                add(JsonObject().apply {
                    addProperty("name", f.name)
                    addProperty("kind", f.kind.name.lowercase())
                    if (f.options.isNotEmpty()) add("options", JsonArray().apply { f.options.forEach { add(it) } })
                    f.defaultValue?.let { addProperty("default", it) }
                })
            }
        })
        add("bodies", JsonArray().apply { d.bodies.forEach { add(it) } })
        add("requires", JsonArray().apply { d.requires.forEach { add(it) } })
        val missing = caps.unsatisfied(d.requires)
        addProperty("available", missing.isEmpty())
        if (missing.isNotEmpty()) add("unavailableReasons", JsonArray().apply { missing.forEach { add(it) } })
        add("blockly", blockly(d))
    }

    /** A ready-to-use Blockly JSON definition (message0 / args0 / connections). */
    private fun blockly(d: BlockDefinition) = JsonObject().apply {
        addProperty("type", d.id)
        val args = JsonArray()
        var n = 0
        val msg = PLACEHOLDER.replace(d.label["ja"]) { m ->
            val name = m.groupValues[1]
            val arg = JsonObject()
            val input = d.input(name)
            val field = d.field(name)
            when {
                input != null -> {
                    arg.addProperty("type", "input_value")
                    arg.addProperty("name", name)
                    if (input.type.id != "any") arg.addProperty("check", input.type.id)
                }
                field != null -> {
                    arg.addProperty("name", name)
                    when (field.kind) {
                        FieldSpec.Kind.DROPDOWN -> {
                            arg.addProperty("type", "field_dropdown")
                            arg.add("options", JsonArray().apply { field.options.forEach { o -> add(JsonArray().apply { add(o); add(o) }) } })
                        }
                        FieldSpec.Kind.CHECKBOX -> {
                            arg.addProperty("type", "field_checkbox")
                            arg.addProperty("checked", field.defaultValue == "true")
                        }
                        FieldSpec.Kind.NUMBER -> arg.addProperty("type", "field_number")
                        FieldSpec.Kind.VARIABLE -> arg.addProperty("type", "field_variable")
                        else -> {
                            arg.addProperty("type", "field_input")
                            arg.addProperty("text", field.defaultValue ?: "")
                        }
                    }
                }
                name in d.bodies -> {
                    arg.addProperty("type", "input_statement")
                    arg.addProperty("name", name)
                }
                else -> return@replace m.value
            }
            args.add(arg)
            "%" + (++n)
        }
        addProperty("message0", msg)
        add("args0", args)
        addProperty("colour", d.category.color)
        when (d.shape) {
            BlockShape.HAT -> addProperty("nextStatement", null as String?)
            BlockShape.STACK, BlockShape.C -> {
                addProperty("previousStatement", null as String?)
                addProperty("nextStatement", null as String?)
            }
            BlockShape.CAP -> addProperty("previousStatement", null as String?)
            BlockShape.REPORTER, BlockShape.BOOLEAN -> d.output?.let { o -> if (o.id != "any") addProperty("output", o.id) else add("output", null) }
        }
        addProperty("inputsInline", true)
    }
}
