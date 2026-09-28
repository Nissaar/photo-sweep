package io.github.nissaar.photosweep.data

import android.app.Application
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Application.dataStore by preferencesDataStore(name = "settings")

/**
 * Settings that belong to this device rather than to the account.
 *
 * Anything that changes what the server does — the delete mode, the collection folder
 * — lives on the server, so the phone and the web UI cannot disagree about it.
 */
class Settings(
    /**
     * The Application, not an Activity. [io.github.nissaar.photosweep.Graph] holds this
     * for the life of the process, and anything shorter-lived would be leaked by it.
     */
    private val context: Application,
) {

    private object Keys {
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val ALLOW_SCREENSHOTS = booleanPreferencesKey("allow_screenshots")
    }

    /** Require biometric or device credential each time the app comes to the front. */
    val appLock: Flow<Boolean> = context.dataStore.data.map { it[Keys.APP_LOCK] ?: false }

    /**
     * Whether to drop FLAG_SECURE so the screen can be captured.
     *
     * Off by default: the app puts an entire photo library on screen, which has no
     * business appearing in screenshots or the recent-apps thumbnail. Turning it on is
     * occasionally necessary — reporting a bug, or taking screenshots for docs.
     */
    val allowScreenshots: Flow<Boolean> =
        context.dataStore.data.map { it[Keys.ALLOW_SCREENSHOTS] ?: false }

    suspend fun setAppLock(value: Boolean) = put(Keys.APP_LOCK, value)

    suspend fun setAllowScreenshots(value: Boolean) = put(Keys.ALLOW_SCREENSHOTS, value)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}
