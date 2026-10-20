package dev.cardvault.core

import dev.cardvault.core.model.Contact
import dev.cardvault.core.model.Kind
import dev.cardvault.core.model.TypedValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportDedupTest {
    private fun c(name: String, phones: List<String> = emptyList(), emails: List<String> = emptyList(), uid: String = Contact.newUid()) =
        Contact(uid, 0, name, phones = phones.map { TypedValue(Kind.MOBILE, it) }, emails = emails.map { TypedValue(Kind.HOME, it) })

    private fun prepare(existing: List<Contact>, vararg incoming: Contact) = Import.prepare(existing, incoming.toList(), now = 1234)

    @Test
    fun importedContactsGetNewUidAndRev() {
        val src = c("Ann", uid = "old", phones = listOf("1"))
        val r = prepare(emptyList(), src)
        val out = r.imported.single()
        assertNotEquals("old", out.uid)
        assertTrue(out.uid.startsWith("urn:uuid:"))
        assertEquals(1234L, out.rev)
        assertEquals(src.copy(uid = out.uid, rev = 1234), out)
        assertEquals(0, r.skippedDuplicates)
    }

    @Test
    fun sameNameAndSharedPhoneByLastNineDigits() {
        val r = prepare(listOf(c("Ann Lee", phones = listOf("+1 (555) 123-4567"))), c("ann  LEE ", phones = listOf("0015551234567")))
        assertTrue(r.imported.isEmpty())
        assertEquals(1, r.skippedDuplicates)
    }

    @Test
    fun differentLastNineDigitsAreNotDuplicates() {
        val r = prepare(listOf(c("Ann", phones = listOf("555 123 4567"))), c("Ann", phones = listOf("555 123 4568")))
        assertEquals(1, r.imported.size)
    }

    @Test
    fun shortPhoneNumbersCompareAllDigits() {
        val ex = listOf(c("Ann", phones = listOf("1234")))
        assertEquals(1, prepare(ex, c("Ann", phones = listOf("01234"))).imported.size)
        assertEquals(0, prepare(ex, c("Ann", phones = listOf("12-34"))).imported.size)
    }

    @Test
    fun sharedEmailIgnoresCaseAndSpaces() {
        val r = prepare(listOf(c("Bob", emails = listOf("Bob@X.com"))), c("Bob", emails = listOf("  bob@x.COM")))
        assertEquals(1, r.skippedDuplicates)
    }

    @Test
    fun sameNameWithDifferentContactDataIsKept() {
        val r = prepare(listOf(c("Bob", phones = listOf("111111111"))), c("Bob", emails = listOf("b@x")), c("Bob", phones = listOf("222222222")))
        assertEquals(2, r.imported.size)
        assertEquals(0, r.skippedDuplicates)
    }

    @Test
    fun bothWithoutPhonesAndEmailsAreDuplicates() {
        val r = prepare(listOf(c("Cy")), c("Cy"))
        assertEquals(1, r.skippedDuplicates)
        // A contact with data does not match a bare contact.
        assertEquals(1, prepare(listOf(c("Cy")), c("Cy", phones = listOf("1"))).imported.size)
    }

    @Test
    fun differentNamesAreNeverDuplicates() {
        val r = prepare(listOf(c("Dee", phones = listOf("111111111"))), c("Dea", phones = listOf("111111111")))
        assertEquals(1, r.imported.size)
    }

    @Test
    fun duplicatesWithinIncomingAreSkipped() {
        val r = prepare(emptyList(), c("Eve", phones = listOf("555000111")), c("eve", phones = listOf("+555000111")), c("Eve", phones = listOf("999999999")))
        assertEquals(2, r.imported.size)
        assertEquals(1, r.skippedDuplicates)
        assertEquals(2, r.imported.map { it.uid }.toSet().size)
    }
}
