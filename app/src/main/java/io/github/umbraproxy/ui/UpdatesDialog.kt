package io.github.umbraproxy.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.umbraproxy.AppUpdates
import io.github.umbraproxy.ApkVariant
import io.github.umbraproxy.BuildConfig
import io.github.umbraproxy.R
import io.github.umbraproxy.UpdateResult
import kotlinx.coroutines.launch

@Composable
fun UpdatesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val variant = remember { AppUpdates.installedVariant(context) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    val release = (result as? UpdateResult.Success)?.release
    val downloadVariant = if (release?.asset?.name?.endsWith("-universal.apk") == true) ApkVariant.UNIVERSAL else variant
    fun openBrowser(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.updates_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME))
                Text(variant.id, style = MaterialTheme.typography.labelMedium)
                if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(when {
                    checking -> R.string.updates_checking
                    result == UpdateResult.SignInRequired -> R.string.updates_sign_in
                    result == UpdateResult.Failed -> R.string.updates_failed
                    release?.isNewer == true -> R.string.updates_available
                    release != null -> R.string.updates_current
                    else -> R.string.updates_description
                }))
                release?.let { Text(it.tag, style = MaterialTheme.typography.titleMedium) }
                Button(
                    enabled = !checking,
                    onClick = {
                        checking = true
                        scope.launch {
                            try { result = AppUpdates.check(variant, BuildConfig.VERSION_NAME) }
                            finally { checking = false }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.updates_check)) }
                if (result == UpdateResult.SignInRequired || (release?.isNewer == true && release.asset != null)) {
                    OutlinedButton(onClick = {
                        val asset = release?.asset
                        if (asset != null && AppUpdates.enqueueDownload(context, asset)) {
                            Toast.makeText(context, R.string.updates_download_started, Toast.LENGTH_LONG).show()
                        } else {
                            openBrowser(asset?.url ?: AppUpdates.latestDownload(variant))
                        }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.updates_download, downloadVariant.id))
                    }
                }
                TextButton(onClick = { openBrowser(AppUpdates.RELEASES_PAGE) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.updates_releases))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
