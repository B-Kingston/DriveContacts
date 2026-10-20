package dev.cardvault.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Runs the shared test vectors in docs/test-vectors. The iOS port uses the same files. */
class VectorsTest {
    private val root = File(System.getProperty("testVectors"))

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    @Test
    fun vcardVectors() {
        val inputs = File(root, "vcard").listFiles { f -> f.name.endsWith(".input.vcf") }!!.sortedBy { it.name }
        assertTrue("no vCard vectors found", inputs.size >= 12)
        for (input in inputs) {
            val name = input.name.removeSuffix(".input.vcf")
            val parsed = VCard.parse(input.readBytes())
            val expected = File(root, "vcard/$name.expected.vcf")
            if (expected.exists()) {
                assertEquals("vector $name", text(expected.readBytes()), text(VCard.serialize(parsed.contacts)))
                // The canonical output must be stable.
                val again = VCard.serialize(VCard.parse(expected.readBytes()).contacts)
                assertArrayEquals("vector $name is not stable", expected.readBytes(), again)
            }
            val meta = File(root, "vcard/$name.meta.json")
            if (meta.exists()) {
                val json = Json.parseToJsonElement(meta.readText()).jsonObject
                json["droppedProperties"]?.let { assertEquals("dropped in $name", it.jsonPrimitive.int, parsed.droppedProperties) }
                json["contacts"]?.let { assertEquals("contacts in $name", it.jsonPrimitive.int, parsed.contacts.size) }
                json["uniqueUids"]?.let { assertEquals("unique uids in $name", it.jsonPrimitive.int, parsed.contacts.map { c -> c.uid }.toSet().size) }
                json["keptUid"]?.let {
                    val first = parsed.contacts.first()
                    assertEquals(it.jsonPrimitive.content, first.uid)
                    assertEquals(json["keptFormattedName"]!!.jsonPrimitive.content, first.formattedName)
                }
            }
        }
    }

    @Test
    fun mergeVectors() {
        val dirs = File(root, "merge").listFiles { f -> f.isDirectory }!!.sortedBy { it.name }
        assertTrue("no merge vectors found", dirs.size >= 15)
        for (dir in dirs) {
            fun load(name: String) = File(dir, "$name.vcf").takeIf { it.exists() }?.let { VCard.parse(it.readBytes()).contacts } ?: emptyList()
            val result = Merge.merge3(load("base"), load("local"), load("remote"))
            assertEquals("merge ${dir.name}", text(File(dir, "expected.vcf").readBytes()), text(VCard.serialize(result.contacts)))
            val expectedConflicts = Json.parseToJsonElement(File(dir, "conflicts.json").readText()).jsonArray.map {
                val o = it.jsonObject
                Triple(o["uid"]!!.jsonPrimitive.content, o["kind"]!!.jsonPrimitive.content, o["fields"]!!.jsonArray.map { f -> f.jsonPrimitive.content })
            }
            val actual = result.conflicts.map { Triple(it.uid, it.kind.name, it.fields.map { f -> f.name }) }
            assertEquals("conflicts ${dir.name}", expectedConflicts, actual)
        }
    }
}
