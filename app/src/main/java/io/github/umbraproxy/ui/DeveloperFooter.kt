package io.github.umbraproxy.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import io.github.umbraproxy.BuildConfig
import io.github.umbraproxy.R

@Composable
fun DeveloperFooter(onOpenUpdates: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    fun openProfile(url: String) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, R.string.link_open_failed, Toast.LENGTH_SHORT).show()
        }
    }
    Column(modifier = modifier.padding(top = 12.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openProfile("https://t.me/RHineix") }) {
                    Icon(painterResource(R.drawable.ic_telegram), stringResource(R.string.profile_telegram),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { openProfile("https://github.com/RHineIx") }) {
                    Icon(painterResource(R.drawable.ic_tabler_brand_github), stringResource(R.string.profile_github),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
        TextButton(onClick = onOpenUpdates) {
            Text(stringResource(R.string.app_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}
