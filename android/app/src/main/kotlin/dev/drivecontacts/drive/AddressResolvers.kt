package dev.drivecontacts.drive

import dev.drivecontacts.auth.DriveAccess
import dev.drivecontacts.auth.UnlockedAddress
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.proton.drive.sdk.PublicAddressResolver
import me.proton.drive.sdk.UserAddressResolver
import me.proton.drive.sdk.entity.Address

private const val FLAG_CAN_VERIFY = 1
private const val FLAG_CAN_ENCRYPT = 2

/** The unlocked address keys for the Drive SDK. */
class AccountUserAddressResolver(private val access: DriveAccess) : UserAddressResolver {
    override suspend fun getAddress(id: String): Address = find(id).toSdk()

    override suspend fun getDefaultAddress(): Address = access.accountKeys().defaultAddress.toSdk()

    override suspend fun <T> getAddressPrimaryPrivateKey(id: String, block: (ByteArray) -> T): T {
        val address = find(id)
        val key = address.keys.getOrNull(address.primaryKeyIndex)
            ?: throw IllegalStateException("The address has no unlocked key")
        return block(key.privateKey)
    }

    override suspend fun <T> getAddressPrivateKeys(id: String, block: (List<ByteArray>) -> T): T =
        block(find(id).keys.map { it.privateKey })

    private suspend fun find(id: String): UnlockedAddress =
        access.accountKeys().addresses.firstOrNull { it.id == id }
            ?: throw NoSuchElementException("Unknown address")

    private fun UnlockedAddress.toSdk() = Address(
        addressId = id,
        order = order,
        emailAddress = email,
        status = when (status) {
            1 -> Address.Status.ENABLED
            2 -> Address.Status.DELETING
            else -> Address.Status.DISABLED
        },
        keys = keys.map {
            Address.Key(
                addressId = id,
                keyId = it.keyId,
                active = true,
                allowedForEncryption = it.flags and FLAG_CAN_ENCRYPT != 0,
                allowedForVerification = it.flags and FLAG_CAN_VERIFY != 0,
            )
        },
        primaryKeyIndex = primaryKeyIndex,
    )
}

/**
 * Public keys for the signature checks. Own addresses use the unlocked keys. Other addresses use the API.
 * The answers of the API stay in memory for 10 minutes.
 */
class AccountPublicAddressResolver(
    private val access: DriveAccess,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : PublicAddressResolver {
    private class Entry(val expiresAt: Long, val keys: List<ByteArray>)

    private val mutex = Mutex()
    private val cache = HashMap<String, Entry>()

    override suspend fun getAddressPublicKeys(emailAddress: String): List<ByteArray> {
        val own = access.accountKeys().addresses.firstOrNull { it.email.equals(emailAddress, ignoreCase = true) }
        if (own != null) {
            return own.keys.filter { it.flags and FLAG_CAN_VERIFY != 0 }.map { it.armoredPublicKey.toByteArray(Charsets.UTF_8) }
        }
        val name = emailAddress.lowercase()
        mutex.withLock {
            cache[name]?.takeIf { it.expiresAt > nowMillis() }?.let { return it.keys }
        }
        val keys = access.accountApi.getPublicKeys(access.sessions, emailAddress)
            .filter { it.flags and FLAG_CAN_VERIFY != 0 }
            .map { it.publicKey.toByteArray(Charsets.UTF_8) }
        mutex.withLock { cache[name] = Entry(nowMillis() + CACHE_MILLIS, keys) }
        return keys
    }

    private companion object {
        const val CACHE_MILLIS = 10 * 60 * 1000L
    }
}
