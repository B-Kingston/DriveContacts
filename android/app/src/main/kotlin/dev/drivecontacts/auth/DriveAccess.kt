package dev.drivecontacts.auth

/** What the Drive code needs from the sign-in. [ProtonAuthService] implements it. */
interface DriveAccess {
    val sessions: SessionManager
    val accountApi: AccountApi

    /**
     * The unlocked keys of the signed-in user. The keys live in memory only.
     * After a process start, this function fetches the user and the addresses and unlocks the keys again.
     * @throws IllegalStateException when nobody is signed in.
     */
    suspend fun accountKeys(): UnlockedAccount

    /** Registers an action that runs on sign-out, before the session secrets are deleted. */
    fun addSignOutHook(hook: suspend () -> Unit)
}
