package com.example.ui.sources

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.documentfile.provider.DocumentFile
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.SafePlayerApp
import com.example.data.MediaFileSupport
import com.example.data.MediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaSourcesScreen(
    app: SafePlayerApp,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sources by app.repository.allSources.collectAsStateWithLifecycle(initialValue = emptyList())
    var visibleSources by remember { mutableStateOf<List<MediaSource>>(emptyList()) }
    var isCheckingSources by remember { mutableStateOf(false) }
    var sourceRefreshKey by remember { mutableIntStateOf(0) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(sources, sourceRefreshKey) {
        isCheckingSources = true
        visibleSources = withContext(Dispatchers.IO) {
            sources.filter { directoryContainsSupportedMedia(context, it.uri) }
        }
        isCheckingSources = false
    }

    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {}

            val folderName = uri.lastPathSegment?.substringAfterLast(':') ?: "Media Folder"
            scope.launch {
                val containsMedia = withContext(Dispatchers.IO) {
                    directoryContainsSupportedMedia(context, uri.toString())
                }
                if (!containsMedia) {
                    runCatching {
                        context.contentResolver.releasePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    }
                    statusMessage = "Folder not added: no audio or video files were found."
                    return@launch
                }

                app.repository.addSource(
                    MediaSource(
                        uri = uri.toString(),
                        displayName = folderName,
                        isScanEnabled = true,
                        lastScanTime = System.currentTimeMillis()
                    )
                )
                statusMessage = "Added media folder: $folderName"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Folders & Sources", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            app.scanner.scanMedia()
                            sourceRefreshKey++
                            statusMessage = "Library scan complete; folders without audio or video are hidden."
                        }
                    }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Scan Sources")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { openDocumentTreeLauncher.launch(null) },
                icon = { Icon(Icons.Default.CreateNewFolder, contentDescription = null) },
                text = { Text("Add Media Folder") }
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
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Media Folder Management", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Only folders containing supported audio or video files are added and displayed. Folder access is granted through Android's system picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            statusMessage?.let { msg ->
                item {
                    Text(msg, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                }
            }

            item {
                Text("Media Folders (${visibleSources.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            }

            if (isCheckingSources) {
                item {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            } else if (visibleSources.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("No folders with audio or video files.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Text("Empty folders and folders containing only documents are hidden.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            } else {
                items(visibleSources) { source ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(source.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text("URI: ${source.uri}", style = MaterialTheme.typography.bodySmall, maxLines = 1, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(source.lastScanTime))
                                Text("Last scan: $dateStr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    app.repository.removeSource(source.id)
                                    statusMessage = "Removed folder: ${source.displayName}"
                                }
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remove", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun directoryContainsSupportedMedia(context: Context, uriString: String): Boolean {
    return try {
        val uri = Uri.parse(uriString)
        val root = DocumentFile.fromTreeUri(context, uri) ?: return false
        val pending = ArrayDeque<Pair<DocumentFile, Int>>()
        pending.addLast(root to 0)
        var visited = 0

        while (pending.isNotEmpty() && visited < MAX_SOURCE_DOCUMENTS) {
            val (document, depth) = pending.removeFirst()
            visited++

            if (document.isFile) {
                val name = document.name ?: continue
                val mimeType = runCatching { context.contentResolver.getType(document.uri) }.getOrNull()
                if (MediaFileSupport.isSupportedMediaFile(name, mimeType) &&
                    runCatching { document.length() > 0L }.getOrDefault(false)
                ) return true
            } else if (document.isDirectory && depth < MAX_SOURCE_DIRECTORY_DEPTH) {
                val children = runCatching { document.listFiles() }.getOrNull() ?: continue
                children.forEach { child -> pending.addLast(child to depth + 1) }
            }
        }
        false
    } catch (_: Exception) {
        false
    }
}

private const val MAX_SOURCE_DOCUMENTS = 10_000
private const val MAX_SOURCE_DIRECTORY_DEPTH = 32
