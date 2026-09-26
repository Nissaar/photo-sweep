package io.github.nissaar.photosweep.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val FILE = "photosweep-account"
private const val KEY_SERVER = "server"
private const val KEY_LOGIN = "login_name"
private const val KEY_PASSWORD = "app_password"
private const val KEY_USER_ID = "user_id"
private const val TAG = "AccountStore"

private const val KEYSTORE = "AndroidKeyStore"
private const val KEY_ALIAS = "photosweep.account"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12
private const val TAG_BITS = 128

/** Who is signed in, as every screen sees it. */
sealed interface AccountState {
    /** The stored account is still being decrypted. */
    data object Loading : AccountState

    data object SignedOut : AccountState

    data class SignedIn(val account: Account) : AccountState
}

/** Where an account is kept between runs. An interface so tests can use memory. */
interface AccountStorage {
    /** False when a saved account will not survive a restart. */
    val isPersistent: Boolean

    fun load(): Account?

    fun save(account: Account)

    fun clear()
}

/**
 * The one source of truth for who is signed in.
 *
 * Every screen derives its signed-in state from [state], and every way of being
 * signed out — the user asking, or the server rejecting the password — ends here.
 * Before, each screen kept its own flag, so one could be signed out while another
 * still thought otherwise, and signing in again after a sign-out left the app on the
 * login screen until it was restarted.
 *
 * Reading the stored account means Keystore work, which is slow enough on some
 * devices to drop frames, so it happens off the main thread and [state] starts at
 * [AccountState.Loading].
 */
class AccountStore(
    private val storage: AccountStorage,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val _state = MutableStateFlow<AccountState>(AccountState.Loading)
    val state: StateFlow<AccountState> = _state.asStateFlow()

    init {
        scope.launch(io) {
            val stored = storage.load()
            _state.compareAndSet(
                AccountState.Loading,
                stored?.let { AccountState.SignedIn(it) } ?: AccountState.SignedOut,
            )
        }
    }

    /** False when this session's sign-in will not survive a restart. */
    val isPersistent: Boolean get() = storage.isPersistent

    fun current(): Account? = (_state.value as? AccountState.SignedIn)?.account

    /** The account once the stored one has been read, or null if there is none. */
    suspend fun awaitLoaded(): Account? =
        (state.first { it !is AccountState.Loading } as? AccountState.SignedIn)?.account

    suspend fun signIn(account: Account) {
        withContext(io) { storage.save(account) }
        _state.value = AccountState.SignedIn(account)
    }

    /**
     * Replaces the stored details of the signed-in account, such as a user id fetched
     * after the fact. Ignored if someone else has signed in since.
     */
    suspend fun update(account: Account) {
        val previous = current() ?: return
        if (previous.key != account.key) return
        withContext(io) { storage.save(account) }
        _state.compareAndSet(AccountState.SignedIn(previous), AccountState.SignedIn(account))
    }

    suspend fun signOut() {
        withContext(io) { storage.clear() }
        _state.value = AccountState.SignedOut
    }

    /**
     * The server rejected [account]'s password: it has been revoked, or expired.
     *
     * Only signs out if that is still the account in use. A request that was already
     * in flight for a previous account must not sign out the one that replaced it.
     */
    fun rejected(account: Account) {
        if (_state.compareAndSet(AccountState.SignedIn(account), AccountState.SignedOut)) {
            storage.clear()
        }
    }
}

/**
 * Keeps the account in private preferences, encrypted.
 *
 * Every value is encrypted with an AES-256-GCM key held in the Android Keystore. The
 * key material never leaves the Keystore and this process cannot read it — what lands
 * on disk is ciphertext, so a stolen device backup carries nothing usable.
 *
 * This used to be `EncryptedSharedPreferences`, which Google has deprecated and no
 * longer maintains. Doing it directly removes a dependency, which F-Droid cares about,
 * and removes the keyset corruption that library was known for on some devices — the
 * failure this class previously had to work around.
 *
 * When the Keystore cannot be used at all, the password is kept in memory for the
 * session and never written to disk. Being asked to sign in again after a restart is a
 * far smaller cost than a credential sitting in clear text.
 */
class KeystoreAccountStorage(context: Context) : AccountStorage {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Null when the Keystore is unusable, which is what keeps the password off disk. */
    private val crypto: AccountCrypto? by lazy { AccountCrypto.open() }

    override val isPersistent: Boolean get() = crypto != null

    override fun load(): Account? {
        val cipher = crypto ?: return null
        val server = read(cipher, KEY_SERVER) ?: return null
        val login = read(cipher, KEY_LOGIN) ?: return null
        val password = read(cipher, KEY_PASSWORD) ?: return null
        return Account(server, login, password, userId = read(cipher, KEY_USER_ID))
    }

    override fun save(account: Account) {
        val cipher = crypto ?: return
        val editor = prefs.edit()
            .putString(KEY_SERVER, cipher.encrypt(account.server))
            .putString(KEY_LOGIN, cipher.encrypt(account.loginName))
            .putString(KEY_PASSWORD, cipher.encrypt(account.appPassword))
        val userId = account.userId
        if (userId != null) editor.putString(KEY_USER_ID, cipher.encrypt(userId)) else editor.remove(KEY_USER_ID)
        editor.apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun read(cipher: AccountCrypto, key: String): String? =
        prefs.getString(key, null)?.let { cipher.decrypt(it) }
}

/**
 * AES-256-GCM against a key the Android Keystore holds.
 *
 * A fresh initialisation vector is generated per encryption — GCM is broken outright by
 * reusing one — and stored in front of the ciphertext, which is what the offsets below
 * are splitting apart.
 */
private class AccountCrypto(private val key: SecretKey) {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    /**
     * Null rather than an exception when the stored value cannot be read back: a key
     * invalidated by a factory reset or a restored backup is a sign-in that has
     * expired, not a crash.
     */
    fun decrypt(stored: String): String? = try {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Stored account could not be decrypted; treating it as signed out", e)
        null
    }

    companion object {
        fun open(): AccountCrypto? = try {
            AccountCrypto(existingKey() ?: generateKey())
        } catch (e: Exception) {
            Log.e(TAG, "Keystore unusable; the password will not be stored", e)
            null
        }

        private fun existingKey(): SecretKey? {
            val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
            return store.getKey(KEY_ALIAS, null) as? SecretKey
        }

        private fun generateKey(): SecretKey {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
