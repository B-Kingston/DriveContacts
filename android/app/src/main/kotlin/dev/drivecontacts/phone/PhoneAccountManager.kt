package dev.drivecontacts.phone

import android.accounts.Account
import android.accounts.AccountManager
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.ContactsContract
import dev.drivecontacts.PhoneSyncSettings
import dev.drivecontacts.data.AtomicFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Creates and removes the DriveContacts account in the phone. The account list of the system is the truth.
 * The flag in SharedPreferences only records the last known state.
 */
class PhoneAccountManager(context: Context, scope: CoroutineScope) : PhoneSyncSettings {
    private val appContext = context.applicationContext
    private val accounts = AccountManager.get(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val mirrorBase = File(appContext.filesDir, "contacts/mirror-base.vcf")
    private val mirrorBaseAccount = File(appContext.filesDir, "contacts/mirror-base.account")
    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))

    override val enabled: StateFlow<Boolean> = state.asStateFlow()

    init {
        reconcile()
        // The user can remove the account in the system settings.
        accounts.addOnAccountsUpdatedListener({ scope.launch(Dispatchers.IO) { reconcile() } }, null, false)
    }

    /** The DriveContacts account, or null. */
    fun currentAccount(): Account? = accounts.getAccountsByType(AndroidPhoneContactsStore.ACCOUNT_TYPE).firstOrNull()

    override suspend fun enable(email: String) {
        withContext(Dispatchers.IO) {
            val existing = currentAccount()
            if (existing != null && existing.name != email) accounts.removeAccountExplicitly(existing)
            val account = Account(email, AndroidPhoneContactsStore.ACCOUNT_TYPE)
            if (currentAccount() == null) {
                val userData = Bundle().apply { putString(AndroidPhoneContactsStore.TOKEN_KEY, AndroidPhoneContactsStore.newToken()) }
                check(accounts.addAccountExplicitly(account, null, userData)) { "The system did not create the account" }
            }
            setUpSync(account)
            record(true)
        }
    }

    override suspend fun disable() {
        withContext(Dispatchers.IO) {
            currentAccount()?.let { accounts.removeAccountExplicitly(it) }
            deleteMirrorBase()
            record(false)
        }
    }

    private fun deleteMirrorBase() {
        AtomicFiles.delete(mirrorBase)
        AtomicFiles.delete(mirrorBaseAccount)
    }

    @Synchronized
    private fun reconcile() {
        val present = currentAccount() != null
        if (!present && state.value) deleteMirrorBase()
        record(present)
    }

    private fun record(value: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        state.value = value
    }

    private fun setUpSync(account: Account) {
        val resolver = appContext.contentResolver
        android.content.ContentResolver.setIsSyncable(account, ContactsContract.AUTHORITY, 1)
        android.content.ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, true)
        val settings = AndroidPhoneContactsStore.syncUri(ContactsContract.Settings.CONTENT_URI, account)
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
        }
        val updated = resolver.update(
            settings,
            values,
            "${ContactsContract.Settings.ACCOUNT_NAME}=? AND ${ContactsContract.Settings.ACCOUNT_TYPE}=?",
            arrayOf(account.name, account.type),
        )
        if (updated == 0) resolver.insert(settings, values)
    }

    private companion object {
        const val PREFS = "drivecontacts-phone"
        const val KEY_ENABLED = "enabled"
    }
}
