package com.example.ui.storage

import androidx.compose.foundation.BorderStroke
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.ui.theme.SafeIconButton
import com.example.ui.theme.SafeTopAppBar
import com.example.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.SafePlayerApp
import com.example.data.MediaItem
import kotlinx.coroutines.launch
import java.text.DecimalFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageDashboardScreen(
    app: SafePlayerApp,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val storageLocations by app.storageManager.storageLocations.collectAsStateWithLifecycle(initialValue = emptyList())
    val missingMediaItems by app.repository.missingMedia.collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()
    var librarySize by remember { mutableStateOf(0L) }
    var relinkingMediaItem by remember { mutableStateOf<MediaItem?>(null) }
    var showMessage by remember { mutableStateOf<String?>(null) }

    val relinkLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        val item = relinkingMediaItem
        if (uri != null && item != null) {
            val grantedModes = (result.data?.flags ?: 0) and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val modesToPersist = grantedModes.takeIf { it != 0 } ?: Intent.FLAG_GRANT_READ_URI_PERMISSION
            val persistedModes = runCatching {
                context.contentResolver.takePersistableUriPermission(uri, modesToPersist)
                modesToPersist
            }.getOrElse {
                if ((grantedModes and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0) {
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }.getOrDefault(0)
                } else {
                    0
                }
            }

            scope.launch {
                val relinkResult = app.storageManager.relinkMedia(item.id, uri.toString())
                showMessage = relinkResult.fold(
                    onSuccess = {
                        val accessNote = when {
                            persistedModes == 0 -> " Document access may not survive an app restart."
                            (persistedModes and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0 ->
                                " This provider granted read-only access; file changes may require selecting it again."
                            else -> ""
                        }
                        "Relinked: ${item.name}.$accessNote"
                    },
                    onFailure = { "Could not relink ${item.name}: ${it.message ?: "unknown error"}" }
                )
                relinkingMediaItem = null
            }
        } else {
            if (item != null) showMessage = "No file was selected."
            relinkingMediaItem = null
        }
    }

    LaunchedEffect(Unit) {
        app.storageManager.discoverAndRefreshLocations()
        librarySize = app.storageManager.getLibrarySize()
    }

    Scaffold(
        topBar = {
            SafeTopAppBar(
                title = { Text("Storage Manager", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    SafeIconButton(
                        icon = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        onClick = onNavigateBack,
                        size = 40.dp,
                        iconSize = 20.dp
                    )
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            app.storageManager.discoverAndRefreshLocations()
                            librarySize = app.storageManager.getLibrarySize()
                        }
                    }) {
                        Icon(Icons.Default.Storage, contentDescription = "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Card(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("App Library Size", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(formatSize(librarySize), style = MaterialTheme.typography.headlineMedium)
                    }
                }
            }

            item {
                Text("Storage Locations", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            items(storageLocations) { location ->
                Card(
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            Text(location.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.label_path) + " ${location.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        
                        val progress = if (location.totalSpace > 0) (location.usedSpace.toFloat() / location.totalSpace).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)
                        )
                        Spacer(Modifier.height(4.dp))
                        
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.format_used_storage, formatSize(location.usedSpace)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(stringResource(R.string.format_free_storage, formatSize(location.availableSpace)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (location.availableSpace < 500 * 1024 * 1024) { // Less than 500MB
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = "Warning", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Low Storage Space!", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(16.dp))
                Text("Storage Maintenance", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                
                showMessage?.let { msg ->
                    Text(msg, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(bottom = 8.dp))
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.72f)),
                        onClick = {
                            scope.launch {
                                val result = app.storageManager.clearCacheDetailed("THUMBNAILS")
                                showMessage = result.fold(
                                    onSuccess = { report ->
                                        val issue = if (report.failures == 0) {
                                            ""
                                        } else {
                                            " ${report.failures} file(s) or folder(s) could not be removed."
                                        }
                                        "Removed ${report.filesDeleted} file(s) (${formatSize(report.bytesCleared)}) from the thumbnails cache.$issue"
                                    },
                                    onFailure = { "Failed to clear the thumbnails cache: ${it.message ?: "unknown error"}" }
                                )
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Clear Thumbnails Cache")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val stats = app.storageManager.reconcileDatabase()
                                val untrackedText = stats.untracked?.let { "$it untracked MediaStore item(s) visible to the app" }
                                    ?: "untracked count unavailable (permission/provider access)"
                                showMessage = "Reconciliation complete: ${stats.missing} missing, $untrackedText, ${stats.unverified} item(s) could not be checked."
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Reconcile DB")
                    }
                }
            }

            if (missingMediaItems.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(8.dp))
                    Text("Missing Files & Integrity Alerts (${missingMediaItems.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                }

                items(missingMediaItems) { missingItem ->
                    Card(
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(missingItem.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                Text(missingItem.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.84f)),
                                onClick = {
                                    relinkingMediaItem = missingItem
                                    relinkLauncher.launch(
                                        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                                            addCategory(Intent.CATEGORY_OPENABLE)
                                            type = if (missingItem.mediaType == com.example.data.MediaType.VIDEO) "video/*" else "audio/*"
                                            addFlags(
                                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                            )
                                        }
                                    )
                                }
                            ) {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Relink")
                            }
                        }
                    }
                }
            }
        }
    }
}

fun formatSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt()
    return DecimalFormat("#,##0.#").format(size / Math.pow(1024.0, digitGroups.toDouble())) + " " + units[digitGroups]
}
