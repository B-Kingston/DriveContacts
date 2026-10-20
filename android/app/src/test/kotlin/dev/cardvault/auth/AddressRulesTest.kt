package dev.cardvault.auth

import org.junit.Assert.assertEquals
import org.junit.Test

class AddressRulesTest {
    @Test
    fun primaryIndexIsTheFirstPrimaryInTheUnlockedList() {
        assertEquals(2, AddressRules.primaryKeyIndex(listOf(false, false, true, true)))
        assertEquals(0, AddressRules.primaryKeyIndex(listOf(true, false)))
    }

    @Test
    fun primaryIndexIsZeroWithoutPrimaryOrKeys() {
        assertEquals(0, AddressRules.primaryKeyIndex(listOf(false, false)))
        assertEquals(0, AddressRules.primaryKeyIndex(emptyList()))
    }

    @Test
    fun defaultAddressIsTheFirstEnabledOne() {
        assertEquals(1, AddressRules.defaultAddressIndex(listOf(0, 1, 1), listOf(true, true, true)))
    }

    @Test
    fun defaultAddressPrefersEnabledAddressWithKeys() {
        assertEquals(2, AddressRules.defaultAddressIndex(listOf(1, 0, 1), listOf(false, true, true)))
    }

    @Test
    fun defaultAddressFallsBackToFirstEnabledThenFirst() {
        assertEquals(1, AddressRules.defaultAddressIndex(listOf(0, 1), listOf(false, false)))
        assertEquals(0, AddressRules.defaultAddressIndex(listOf(0, 2), listOf(true, true)))
        assertEquals(-1, AddressRules.defaultAddressIndex(emptyList(), emptyList()))
    }
}
