package dev.cardvault.phone

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * The authenticator of account type `dev.cardvault.account`. The app creates the account itself.
 * "Add account" in the system settings opens the app. All other operations are not supported.
 */
class Authenticator(private val context: Context) : AbstractAccountAuthenticator(context) {
    override fun addAccount(
        response: AccountAuthenticatorResponse?,
        accountType: String?,
        authTokenType: String?,
        requiredFeatures: Array<out String>?,
        options: Bundle?,
    ): Bundle {
        val intent = Intent()
            .setClassName(context, "dev.cardvault.ui.MainActivity")
            .putExtra(AccountManager.KEY_ACCOUNT_AUTHENTICATOR_RESPONSE, response)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return Bundle().apply { putParcelable(AccountManager.KEY_INTENT, intent) }
    }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = unsupported()

    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle =
        unsupported()

    override fun getAuthToken(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun getAuthTokenLabel(authTokenType: String?): String? = null

    override fun updateCredentials(
        response: AccountAuthenticatorResponse?,
        account: Account?,
        authTokenType: String?,
        options: Bundle?,
    ): Bundle = unsupported()

    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, false) }

    private fun unsupported(): Bundle = Bundle().apply {
        putInt(AccountManager.KEY_ERROR_CODE, AccountManager.ERROR_CODE_UNSUPPORTED_OPERATION)
        putString(AccountManager.KEY_ERROR_MESSAGE, "Not supported")
    }
}

class AuthenticatorService : Service() {
    private val authenticator by lazy { Authenticator(this) }

    override fun onBind(intent: Intent?): IBinder? = authenticator.iBinder
}
