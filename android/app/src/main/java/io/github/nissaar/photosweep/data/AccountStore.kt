package io.github.nissaar.photosweep.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

private const val FILE = "photosweep-account"
private const val KEY_SERVER = "server"
private const val KEY_LOGIN = "login_name"
private const val KEY_PASSWORD = "app_password"
private const val TAG = "AccountStore"

/**
 * Where the app password lives.
 *
 * Held in `EncryptedSharedPreferences`, so the bytes on disk are encrypted under a key
 * the Android Keystore holds and the app itself never sees. That is the difference
 * between a stolen device backup containing a working credential and containing
 * nothing useful.
 *
 * When the Keystore cannot be used, the password is kept **in memory for this session
 * only** and never written to disk. The earlier version fell back to plain
 * preferences, which meant a device with a broken Keystore silently stored a working
 * credential in clear text while the app went on claiming it was encrypted. Being
 * asked to sign in again after a restart is a far smaller cost than that.
 */
class AccountStore(context: Context) {

    /** Null when the Keystore is unusable, which is what keeps the password off disk. */
    private val prefs: SharedPreferences? = openEncrypted(context)

    /** False when this session's sign-in will not survive a restart. */
    val isPersistent: Boolean get() = prefs != null

    @Volatile
    private var cached: Account? = load()

    fun current(): Account? = cached

    fun isSignedIn(): Boolean = cached != null

    fun save(account: Account) {
        prefs?.edit()
            ?.putString(KEY_SERVER, account.server)
            ?.putString(KEY_LOGIN, account.loginName)
            ?.putString(KEY_PASSWORD, account.appPassword)
            ?.apply()
        cached = account
    }

    fun clear() {
        prefs?.edit()?.clear()?.apply()
        cached = null
    }

    private fun load(): Account? {
        val store = prefs ?: return null
        val server = store.getString(KEY_SERVER, null) ?: return null
        val login = store.getString(KEY_LOGIN, null) ?: return null
        val password = store.getString(KEY_PASSWORD, null) ?: return null
        return Account(server, login, password)
    }
}

/**
 * Opens the encrypted store, or returns null rather than opening an unencrypted one.
 *
 * The usual cause of a failure here is a keyset that no longer matches the file —
 * typically after the preferences have been restored onto a device whose Keystore
 * never had the original key. Nothing in that file can be read again, so throwing it
 * away and starting a fresh one costs a sign-in and fixes the device permanently.
 */
private fun openEncrypted(context: Context): SharedPreferences? {
    fun create(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        FILE,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    return try {
        create()
    } catch (first: Exception) {
        Log.w(TAG, "Encrypted store unreadable; discarding it and retrying", first)
        try {
            context.deleteSharedPreferences(FILE)
            create()
        } catch (second: Exception) {
            // Out of options. The app still works for this session; it just will not
            // remember the password, which is the only safe thing left to do.
            Log.e(TAG, "Keystore unusable; the password will not be stored", second)
            null
        }
    }
}
