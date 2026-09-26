package io.github.nissaar.photosweep

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import coil.imageLoader
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import io.github.nissaar.photosweep.api.LoginFlow
import io.github.nissaar.photosweep.api.PhotoSweepApi
import io.github.nissaar.photosweep.data.AccountStore
import io.github.nissaar.photosweep.data.KeystoreAccountStorage
import io.github.nissaar.photosweep.data.Repository
import io.github.nissaar.photosweep.data.Session
import io.github.nissaar.photosweep.data.Settings
import io.github.nissaar.photosweep.data.VerdictOutbox
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The object graph.
 *
 * Small enough that a dependency-injection framework would cost more than it saves,
 * and explicit enough that what depends on what is readable in one screen.
 */
object Graph {
    /** Work that must finish even when the screen that started it goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var accounts: AccountStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var api: PhotoSweepApi
        private set
    lateinit var repository: Repository
        private set
    lateinit var session: Session
        private set
    lateinit var loginFlow: LoginFlow
        private set
    lateinit var http: OkHttpClient
        private set

    fun open(application: Application) {
        accounts = AccountStore(KeystoreAccountStorage(application), appScope)
        settings = Settings(application)

        http = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            // Generous, because a first index scan on a large library legitimately
            // keeps a request open for a while before it answers.
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()

        api = PhotoSweepApi(http, accountProvider = { accounts.current() }, onUnauthorised = accounts::rejected)
        val outbox = VerdictOutbox(
            File(application.filesDir, "outbox.json"),
            legacyOwner = { accounts.awaitLoaded()?.key },
        )
        repository = Repository(api, outbox) { accounts.current()?.key }
        session = Session(
            accounts = accounts,
            revoke = api::revokeAppPassword,
            clearCaches = { clearImageCaches(application) },
            scope = appScope,
        )
        loginFlow = LoginFlow(http)
    }

    /** Previews of the previous account's photos, which have no business outliving it. */
    @OptIn(ExperimentalCoilApi::class)
    private suspend fun clearImageCaches(application: Application) {
        val loader = application.imageLoader
        loader.memoryCache?.clear()
        withContext(Dispatchers.IO) { loader.diskCache?.clear() }
    }
}

class App : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        Graph.open(this)
    }

    /**
     * Previews come from the user's own Nextcloud and need the account's credentials,
     * which a plain image request would not carry.
     *
     * The header is attached per request from the store rather than baked in, so
     * signing out takes effect immediately instead of leaving a client that can still
     * fetch the previous account's photos. It is attached only to URLs on the
     * account's own server, compared by host and path rather than by string prefix.
     */
    override fun newImageLoader(): ImageLoader {
        val client = Graph.http.newBuilder()
            .addInterceptor { chain ->
                val account = Graph.accounts.current()
                val request = chain.request()
                val authorised = if (account != null && account.owns(request.url)) {
                    request.newBuilder()
                        .header("Authorization", account.basicAuthHeader())
                        .build()
                } else {
                    request
                }
                chain.proceed(authorised)
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("previews"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .respectCacheHeaders(false)
            .build()
    }
}
