package dev.drivecontacts.auth

import com.proton.gopenpgp.crypto.Crypto
import com.proton.gopenpgp.crypto.Key
import com.proton.gopenpgp.crypto.KeyRing

/** An unlocked address key. [privateKey] is the binary OpenPGP key without passphrase. Keep it in memory only. */
class UnlockedKey(
    val keyId: String,
    val privateKey: ByteArray,
    val armoredPublicKey: String,
    /** API `Flags`: bit 1 = can verify, bit 2 = can encrypt. */
    val flags: Int,
)

/** An address with its unlocked keys. [primaryKeyIndex] is an index in [keys]. */
class UnlockedAddress(
    val id: String,
    val email: String,
    /** API `Status`: 0 disabled, 1 enabled, 2 deleting. */
    val status: Int,
    val order: Int,
    val keys: List<UnlockedKey>,
    val primaryKeyIndex: Int,
)

/** All addresses in API order. [defaultAddress] is one of [addresses]. */
class UnlockedAccount(val addresses: List<UnlockedAddress>, val defaultAddress: UnlockedAddress)

class KeyUnlockException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Pure rules for the choice of the primary key and the default address. */
object AddressRules {
    /** The index of the first primary key in the list of unlocked keys, or 0. */
    fun primaryKeyIndex(primaryFlags: List<Boolean>): Int = primaryFlags.indexOf(true).coerceAtLeast(0)

    /**
     * The first enabled address that has unlocked keys. Else the first enabled address. Else the first address.
     * Returns -1 for an empty list.
     */
    fun defaultAddressIndex(statuses: List<Int>, hasKeys: List<Boolean>): Int {
        if (statuses.isEmpty()) return -1
        val enabledWithKeys = statuses.indices.firstOrNull { statuses[it] == STATUS_ENABLED && hasKeys[it] }
        if (enabledWithKeys != null) return enabledWithKeys
        return statuses.indexOf(STATUS_ENABLED).coerceAtLeast(0)
    }

    const val STATUS_ENABLED = 1
}

/** Unlocks the user keys and the address keys with the gopenpgp mobile API. */
object KeyUnlocker {
    /**
     * Blocking and CPU heavy. Call it from a background dispatcher.
     * Keys with Active = 0 are skipped. An address key that fails the checks is skipped.
     * @throws KeyUnlockException when no user key or no address key can be unlocked.
     */
    fun unlock(keyPassword: String, user: UserDto, addresses: List<AddressDto>): UnlockedAccount {
        val passphrase = keyPassword.toByteArray(Charsets.UTF_8)
        try {
            val userKeys = user.keys.filter { it.active == 1 }.mapNotNull { key ->
                runCatching { Crypto.newKeyFromArmored(key.privateKey).unlock(passphrase) }.getOrNull()
            }
            if (userKeys.isEmpty()) throw KeyUnlockException("No user key can be unlocked")
            val userRing = Crypto.newKeyRing(userKeys.first())
            userKeys.drop(1).forEach { userRing.addKey(it) }
            try {
                val result = addresses.map { unlockAddress(it, userRing, passphrase) }
                if (result.none { it.keys.isNotEmpty() }) throw KeyUnlockException("No address key can be unlocked")
                val index = AddressRules.defaultAddressIndex(result.map { it.status }, result.map { it.keys.isNotEmpty() })
                return UnlockedAccount(result, result[index])
            } finally {
                userRing.clearPrivateParams()
                userKeys.forEach { it.clearPrivateParams() }
            }
        } finally {
            passphrase.fill(0)
        }
    }

    private fun unlockAddress(address: AddressDto, userRing: KeyRing, keyPassphrase: ByteArray): UnlockedAddress {
        val keys = ArrayList<UnlockedKey>()
        val primaries = ArrayList<Boolean>()
        for (dto in address.keys) {
            if (dto.active != 1) continue
            val unlocked = unlockAddressKey(dto, userRing, keyPassphrase) ?: continue
            keys += unlocked
            primaries += dto.primary == 1
        }
        return UnlockedAddress(address.id, address.email, address.status, address.order, keys, AddressRules.primaryKeyIndex(primaries))
    }

    private fun unlockAddressKey(dto: AddressKeyDto, userRing: KeyRing, keyPassphrase: ByteArray): UnlockedKey? {
        val armored = dto.privateKey ?: return null
        return try {
            val token = dto.token
            val signature = dto.signature
            val unlocked: Key = if (token != null && signature != null) {
                val plain = userRing.decrypt(Crypto.newPGPMessageFromArmored(token), null, 0L)
                userRing.verifyDetached(plain, Crypto.newPGPSignatureFromArmored(signature), 0L)
                val tokenBytes = plain.binary
                try {
                    Crypto.newKeyFromArmored(armored).unlock(tokenBytes)
                } finally {
                    tokenBytes.fill(0)
                }
            } else {
                Crypto.newKeyFromArmored(armored).unlock(keyPassphrase)
            }
            try {
                UnlockedKey(dto.id, unlocked.serialize(), unlocked.armoredPublicKey, dto.flags)
            } finally {
                unlocked.clearPrivateParams()
            }
        } catch (e: Exception) {
            null
        }
    }
}
