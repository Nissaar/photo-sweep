package io.github.nissaar.photosweep.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nissaar.photosweep.api.CleanupMode
import io.github.nissaar.photosweep.api.ServerConfig

@Composable
fun SettingsScreen(
    config: ServerConfig,
    trashAvailable: Boolean,
    serverName: String,
    loginName: String,
    /** False when the Keystore could not be used and the sign-in lasts this session only. */
    signInPersistent: Boolean,
    allowScreenshots: Boolean,
    appLock: Boolean,
    onMode: (String) -> Unit,
    onTargetFolder: (String) -> Unit,
    onSkipDecided: (Boolean) -> Unit,
    onAllowScreenshots: (Boolean) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onRebuildIndex: () -> Unit,
    onSignOut: () -> Unit,
) {
    var folder by remember(config.targetFolder) { mutableStateOf(config.targetFolder) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Section("What happens when you confirm a deletion")
        Text(
            "Stored on the server, so this app and the web interface always agree.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))

        Choice(
            selected = config.mode == CleanupMode.TRASH,
            title = "Move to the Nextcloud trash",
            body = if (trashAvailable) {
                "A real deletion. The files leave your library and your server's " +
                    "retention policy decides how long they stay recoverable."
            } else {
                "The trash app is disabled on this server, so this deletes permanently " +
                    "and cannot be undone."
            },
            onClick = { onMode(CleanupMode.TRASH) },
        )

        Choice(
            selected = config.mode == CleanupMode.FOLDER,
            title = "Collect them in a folder",
            body = "Deletes nothing. The files are moved together so you can look " +
                "through them in Files and delete them yourself.",
            onClick = { onMode(CleanupMode.FOLDER) },
        )

        if (config.mode == CleanupMode.FOLDER) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = folder,
                onValueChange = { folder = it },
                label = { Text("Collection folder") },
                singleLine = true,
                supportingText = {
                    Text("Created if it does not exist, and left out of the index so collected photos do not come back around.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = { onTargetFolder(folder) },
                enabled = folder.isNotBlank() && folder != config.targetFolder,
            ) { Text("Save folder") }
        }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Section("Reviewing")
        Toggle(
            checked = config.skipDecided,
            title = "Hide photos you have already judged",
            body = "On by default, so reopening a month picks up where you left off.",
            onChange = onSkipDecided,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Section("This device")
        Toggle(
            checked = appLock,
            title = "Unlock with biometrics",
            body = "Ask for your fingerprint or device PIN each time the app comes back " +
                "to the front, after more than a few seconds away.",
            onChange = onAppLock,
        )
        Toggle(
            checked = allowScreenshots,
            title = "Allow screenshots",
            body = "Off by default. The app puts an entire photo library on screen, " +
                "which has no business appearing in screenshots or the recent-apps preview.",
            onChange = onAllowScreenshots,
        )

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Section("Index")
        Text(
            "Rebuilding reads every photo again and works out its date from scratch. " +
                "Your verdicts are kept.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onRebuildIndex) { Text("Rebuild index") }

        Spacer(Modifier.height(24.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        Section("Account")
        Text(
            "$loginName on $serverName",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (!signInPersistent) {
            Spacer(Modifier.height(4.dp))
            Text(
                "This phone's secure storage is not working, so your sign-in is kept in " +
                    "memory only. You will be asked to sign in again after the app closes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Signing out revokes this device's app password on the server and clears " +
                "the previews stored on the phone. If the phone is offline at the time, " +
                "revoke it yourself from Settings → Security in Nextcloud. Do that too if " +
                "the device is lost.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSignOut) {
            Text("Sign out", color = MaterialTheme.colorScheme.error)
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Choice(selected: Boolean, title: String, body: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 8.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Toggle(checked: Boolean, title: String, body: String, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
