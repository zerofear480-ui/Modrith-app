package com.modrith.ui.install

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallConfirmationScreen(
    onBack: () -> Unit,
    viewModel: InstallViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let { viewModel.selectLauncherTree(it.toString()) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Confirm installation") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = viewModel::startInstallation,
                        enabled = state.canInstall,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Install")
                    }
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.pack?.let { pack ->
                item {
                    SummarySection("Pack") {
                        Text(pack.name, style = MaterialTheme.typography.titleLarge)
                        Text("Version ${pack.versionId}")
                        Text("Minecraft ${pack.minecraftVersion}")
                        Text("${pack.loader} ${pack.loaderVersion}")
                        Text("${pack.modCount} mods | ${pack.totalFiles} files")
                        Text(formatBytes(pack.totalDownloadBytes))
                    }
                }
                if (pack.warnings.isNotEmpty()) {
                    item {
                        SummarySection("Pack notices") {
                            pack.warnings.forEach { warning ->
                                Text(
                                    warning,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                SummarySection("Launcher destination") {
                    Text(
                        "Select the launcher storage tree exposed by Android's document picker.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { treePicker.launch(null) },
                        enabled = !state.busy,
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(if (state.launcher == null) "Choose launcher tree" else "Change tree")
                    }
                    if (state.busy) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp)
                            Text("Checking launcher")
                        }
                    }
                }
            }
            state.launcher?.let { launcher ->
                item {
                    Text(
                        launcher.displayName,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                items(launcher.instances, key = LauncherInstanceUi::profileId) { instance ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.selectedLauncherProfileId == instance.profileId,
                                onClick = {
                                    viewModel.selectLauncherInstance(instance.profileId)
                                },
                            )
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.selectedLauncherProfileId == instance.profileId,
                            onClick = null,
                        )
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(instance.name, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                instance.versionId,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (launcher.warnings.isNotEmpty()) {
                    item {
                        SummarySection("Launcher notices") {
                            launcher.warnings.forEach { Text(it) }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallationProgressScreen(
    viewModel: InstallViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val progress = state.progress

    Scaffold(
        topBar = { TopAppBar(title = { Text("Installing") }) },
        bottomBar = {
            Surface(shadowElevation = 4.dp) {
                OutlinedButton(
                    onClick = viewModel::cancelInstallation,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Cancel installation")
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(progress.title, style = MaterialTheme.typography.headlineSmall)
            Text(
                progress.message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LinearProgressIndicator(
                progress = { progress.percentage.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${(progress.percentage * 100).toInt()}%")
                Text("${progress.filesCompleted} / ${progress.totalFiles} files")
            }
            progress.currentFile?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                buildProgressDetail(progress),
                style = MaterialTheme.typography.bodyMedium,
            )
            HorizontalDivider()
            Text("Live log", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.logs, key = { "${it.timestampEpochMillis}:${it.source}:${it.message}" }) {
                    LogLine(it)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallSuccessScreen(
    onDone: () -> Unit,
    viewModel: InstallViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val result = state.result

    Scaffold(topBar = { TopAppBar(title = { Text("Installation complete") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(
                result?.packName ?: "Modpack installed",
                style = MaterialTheme.typography.headlineSmall,
            )
            Text(
                "Files were installed through the selected SAF tree. Launcher data was inspected read-only.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            result?.let {
                Text("Destination: ${it.launcherInstanceName}")
                Text("${it.installedFiles} files | ${formatBytes(it.installedBytes)}")
            }
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = {
                    viewModel.reset()
                    onDone()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Done")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallErrorScreen(
    onChooseAnother: () -> Unit,
    viewModel: InstallViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val error = state.error

    Scaffold(topBar = { TopAppBar(title = { Text("Installation stopped") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                error?.title ?: "Installation could not continue",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                error?.message ?: "An unexpected installation error occurred.",
                style = MaterialTheme.typography.bodyLarge,
            )
            error?.action?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            error?.code?.let {
                Text(
                    "Error code: $it",
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            HorizontalDivider()
            Text("Recent log", style = MaterialTheme.typography.titleMedium)
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.logs.takeLast(50)) { LogLine(it) }
            }
            if (error?.recoverable == true) {
                Button(
                    onClick = viewModel::retryInstallation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text("Retry")
                }
            }
            OutlinedButton(
                onClick = {
                    viewModel.reset()
                    onChooseAnother()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Choose another pack")
            }
        }
    }
}

@Composable
private fun SummarySection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}

@Composable
private fun LogLine(log: InstallLogUi) {
    val time = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(log.timestampEpochMillis))
    Text(
        "$time ${log.level} ${log.source}  ${log.message}",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
    )
}

private fun buildProgressDetail(progress: InstallProgressUi): String {
    val transfer = if (progress.totalBytes == null) {
        formatBytes(progress.downloadedBytes)
    } else {
        "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
    }
    val speed = progress.bytesPerSecond.takeIf { it > 0 }?.let {
        " | ${formatBytes(it)}/s"
    }.orEmpty()
    val remaining = progress.estimatedRemainingMillis?.takeIf { it > 0 }?.let {
        " | about ${formatDuration(it)} left"
    }.orEmpty()
    return "$transfer$speed$remaining"
}

private fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "Download size unavailable"
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit += 1
    }
    return "%.1f %s".format(value, units[unit.coerceAtLeast(0)])
}

private fun formatDuration(millis: Long): String {
    val seconds = (millis / 1_000).coerceAtLeast(1)
    return if (seconds < 60) {
        "$seconds sec"
    } else {
        "${(seconds + 59) / 60} min"
    }
}
