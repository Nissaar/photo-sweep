package io.github.nissaar.photosweep

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import io.github.nissaar.photosweep.data.AccountState
import io.github.nissaar.photosweep.ui.LoginScreen
import io.github.nissaar.photosweep.ui.MonthsScreen
import io.github.nissaar.photosweep.ui.ReviewScreen
import io.github.nissaar.photosweep.ui.SettingsScreen
import io.github.nissaar.photosweep.ui.SwipeScreen
import io.github.nissaar.photosweep.ui.monthLabel
import io.github.nissaar.photosweep.ui.theme.PhotoSweepTheme
import io.github.nissaar.photosweep.vm.AppViewModel
import io.github.nissaar.photosweep.vm.LoginViewModel
import io.github.nissaar.photosweep.vm.MonthsViewModel
import io.github.nissaar.photosweep.vm.ReviewViewModel
import io.github.nissaar.photosweep.vm.SwipeViewModel

private enum class Tab { MONTHS, REVIEW, SETTINGS }

/**
 * How long the app can be in the background before the lock asks again. Long enough
 * to glance at a notification or answer a message, short enough that a phone put
 * down on a table is locked by the time someone else picks it up.
 */
private const val LOCK_GRACE_MS = 10_000L

class MainActivity : FragmentActivity() {

    /**
     * True while the app lock hides the content. Starts true so nothing is shown
     * before the setting has been read.
     */
    private var locked by mutableStateOf(true)

    /** The prompt can itself send the activity through stop and start on some devices. */
    private var authenticating = false

    /** When the app last went to the background after being unlocked. */
    private var backgroundedAt: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Secure before the first frame is drawn. Waiting for the setting to load let
        // the first screen through into the recent-apps thumbnail.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            PhotoSweepTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(Modifier.fillMaxSize()) {
                        // Kept composed behind the lock, so unlocking returns to the
                        // same tab and month, but hidden from accessibility services.
                        Box(if (locked) Modifier.fillMaxSize().clearAndSetSemantics {} else Modifier.fillMaxSize()) {
                            Root()
                        }
                        if (locked) LockScreen(onUnlock = ::promptUnlock)
                    }
                }
            }
        }

        observeScreenshotSetting()
        observeLifecycle()
    }

    /**
     * FLAG_SECURE is on unless the user turns it off: this app puts an entire photo
     * library on screen, which has no business appearing in a screen recording or in
     * the recent-apps preview. Followed live, so the switch works without leaving
     * the app.
     */
    private fun observeScreenshotSetting() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Graph.settings.allowScreenshots.collect { allow ->
                    if (allow) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    } else {
                        window.setFlags(
                            WindowManager.LayoutParams.FLAG_SECURE,
                            WindowManager.LayoutParams.FLAG_SECURE,
                        )
                    }
                }
            }
        }
    }

    /**
     * The optional lock, asked for every time the app comes back to the front.
     *
     * It used to be asked once per process, which on a phone with memory to spare
     * could mean once in several days.
     */
    private fun observeLifecycle() {
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> checkLock()
                    Lifecycle.Event.ON_STOP -> if (!authenticating && !locked) {
                        backgroundedAt = SystemClock.elapsedRealtime()
                    }
                    else -> Unit
                }
            },
        )
    }

    private fun checkLock() {
        if (authenticating) return

        lifecycleScope.launch {
            if (!Graph.settings.appLock.first()) {
                locked = false
                return@launch
            }
            val since = backgroundedAt
            if (!locked && since != null && SystemClock.elapsedRealtime() - since < LOCK_GRACE_MS) {
                return@launch
            }
            locked = true
            promptUnlock()
        }
    }

    private fun promptUnlock() {
        if (authenticating) return

        val allowed = BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

        // Nothing to authenticate against — no biometrics enrolled and no device
        // lock. Refusing entry here would lock the user out of their own app with
        // no way back in.
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            locked = false
            return
        }

        authenticating = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    backgroundedAt = null
                    locked = false
                }

                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    // Stays locked. The lock screen offers to try again, and back
                    // leaves the app as it would from anywhere else.
                    authenticating = false
                }
            },
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Photo Sweep")
                .setAllowedAuthenticators(allowed)
                .build(),
        )
    }

    /** @return false when no browser could be opened at all */
    fun openInBrowser(url: String): Boolean = try {
        CustomTabsIntent.Builder().build().launchUrl(this, Uri.parse(url))
        true
    } catch (e: Exception) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        } catch (e: Exception) {
            false
        }
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Surface(
        Modifier
            .fillMaxSize()
            // Swallows every touch, so nothing behind the lock can be operated blind.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, Modifier.size(40.dp))
            Spacer(Modifier.height(12.dp))
            Text("Photo Sweep is locked", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

@Composable
private fun Root() {
    val accountState by Graph.accounts.state.collectAsState()

    when (val current = accountState) {
        // A moment while the stored account is decrypted; nothing to show yet.
        AccountState.Loading -> Box(Modifier.fillMaxSize())

        AccountState.SignedOut -> SignIn()

        // Keyed to the account, so a different account starts on fresh screens
        // instead of inheriting the last one's tabs, lists and open month.
        is AccountState.SignedIn -> key(current.account.key) {
            SignedIn(accountKey = current.account.key)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedIn(accountKey: String) {
    val appViewModel: AppViewModel = viewModel()
    val appState by appViewModel.state.collectAsState()

    // Saveable, so a foldable being opened or closed — which recreates the activity —
    // does not throw the user back to the first tab.
    var tab by rememberSaveable { mutableStateOf(Tab.MONTHS) }
    var openMonth by rememberSaveable { mutableStateOf<String?>(null) }

    val monthsViewModel: MonthsViewModel = viewModel(key = "months:$accountKey")
    val monthsState by monthsViewModel.state.collectAsState()

    val allowScreenshots by Graph.settings.allowScreenshots.collectAsState(initial = false)
    val appLock by Graph.settings.appLock.collectAsState(initial = false)
    val scope = rememberCoroutineScope()

    // Coming back from a month is a back gesture, not a button hunt.
    BackHandler(enabled = openMonth != null) { openMonth = null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            openMonth != null -> monthLabel(openMonth!!)
                            tab == Tab.MONTHS -> "Photo Sweep"
                            tab == Tab.REVIEW -> "Marked for deletion"
                            else -> "Settings"
                        },
                    )
                },
            )
        },
        bottomBar = {
            if (openMonth == null) {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.MONTHS,
                        onClick = { tab = Tab.MONTHS },
                        icon = {
                            BadgedBox(badge = {
                                if (monthsState.toReviewCount > 0) Badge { Text("${monthsState.toReviewCount}") }
                            }) { Icon(Icons.Default.CalendarMonth, contentDescription = null) }
                        },
                        label = { Text("Months") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.REVIEW,
                        onClick = { tab = Tab.REVIEW },
                        icon = {
                            BadgedBox(badge = {
                                if (appState.summary.pendingDeletes > 0) {
                                    Badge { Text("${appState.summary.pendingDeletes}") }
                                }
                            }) { Icon(Icons.Default.DeleteSweep, contentDescription = null) }
                        },
                        label = { Text("Marked") },
                    )
                    NavigationBarItem(
                        selected = tab == Tab.SETTINGS,
                        onClick = { tab = Tab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text("Settings") },
                    )
                }
            }
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val month = openMonth
            when {
                month != null -> {
                    val swipeViewModel: SwipeViewModel = viewModel(key = "swipe:$accountKey:$month")
                    val swipeState by swipeViewModel.state.collectAsState()

                    LaunchedEffect(month) { swipeViewModel.load(month) }
                    DisposableEffect(month) {
                        onDispose { appViewModel.refresh() }
                    }

                    SwipeScreen(
                        state = swipeState,
                        verdictsInMonth = monthsState.months.firstOrNull { it.month == month }?.reviewed,
                        onKeep = swipeViewModel::keep,
                        onDelete = swipeViewModel::delete,
                        onUndo = swipeViewModel::undo,
                        onReviewAgain = swipeViewModel::reviewAgain,
                        onBack = { openMonth = null },
                    )
                }

                tab == Tab.MONTHS -> {
                    // Every time the grid comes back into view, including from a month.
                    LaunchedEffect(Unit) { monthsViewModel.load() }

                    MonthsScreen(
                        state = monthsState,
                        scanning = appState.scan.running,
                        indexedCount = appState.summary.indexed,
                        onFilter = monthsViewModel::setFilter,
                        onOpen = { openMonth = it },
                        onReopen = monthsViewModel::reopen,
                    )
                }

                tab == Tab.REVIEW -> {
                    val reviewViewModel: ReviewViewModel = viewModel(key = "review:$accountKey")
                    val reviewState by reviewViewModel.state.collectAsState()

                    // Every time the tab is opened: the list is what the user confirms,
                    // so it must not be one left over from an earlier visit.
                    LaunchedEffect(Unit) { reviewViewModel.load() }
                    LaunchedEffect(reviewState.result) {
                        if (reviewState.result != null) appViewModel.refresh()
                    }

                    ReviewScreen(
                        state = reviewState,
                        mode = appState.config.mode,
                        trashAvailable = appState.trashAvailable,
                        onKeepAfterAll = reviewViewModel::keepAfterAll,
                        onApply = reviewViewModel::apply,
                        onCancelPermanent = reviewViewModel::cancelPermanent,
                        onRestore = reviewViewModel::restore,
                    )
                }

                else -> SettingsScreen(
                    config = appState.config,
                    trashAvailable = appState.trashAvailable,
                    serverName = appState.serverName,
                    loginName = Graph.accounts.current()?.loginName ?: "",
                    signInPersistent = Graph.accounts.isPersistent,
                    allowScreenshots = allowScreenshots,
                    appLock = appLock,
                    onMode = appViewModel::setMode,
                    onTargetFolder = appViewModel::setTargetFolder,
                    onSkipDecided = appViewModel::setSkipDecided,
                    onAllowScreenshots = { scope.launch { Graph.settings.setAllowScreenshots(it) } },
                    onAppLock = { scope.launch { Graph.settings.setAppLock(it) } },
                    onRebuildIndex = { appViewModel.startScan(full = true) },
                    onSignOut = appViewModel::signOut,
                )
            }
        }
    }
}

@Composable
private fun SignIn() {
    val loginViewModel: LoginViewModel = viewModel()
    val state by loginViewModel.state.collectAsState()
    val activity = LocalActivity.current as MainActivity

    LaunchedEffect(state.openUrl) {
        state.openUrl?.let {
            if (activity.openInBrowser(it)) {
                loginViewModel.urlOpened()
            } else {
                loginViewModel.browserUnavailable(it)
            }
        }
    }

    LoginScreen(
        state = state,
        onServerUrlChange = loginViewModel::setServerUrl,
        onStart = loginViewModel::start,
        onCancel = loginViewModel::cancel,
    )
}
