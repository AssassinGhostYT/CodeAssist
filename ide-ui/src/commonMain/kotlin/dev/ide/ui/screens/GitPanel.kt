package dev.ide.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.GitFile
import dev.ide.ui.backend.GitService
import dev.ide.ui.backend.GitStatus
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.theme.Ide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Source-control panel: lists working-tree changes (staged vs unstaged), lets the user prepare/unprepare
 * each file, shows a unified diff on tap, and commits / pushes through the [GitService]. Falls back to a
 * plain message when git isn't available in the environment.
 */
@Composable
fun GitPanel(backend: IdeBackend) {
    val git = backend.git
    val scope = rememberCoroutineScope()
    var files by remember { mutableStateOf<List<GitFile>>(emptyList()) }
    var branch by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("") }
    var statusText by remember { mutableStateOf("") }
    var diff by remember { mutableStateOf<GitFile?>(null) }
    var diffText by remember { mutableStateOf("") }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val f = runCatching { git.status() }.getOrDefault(emptyList())
            val b = runCatching { git.branch() }.getOrNull()
            withContext(Dispatchers.Main) {
                files = f
                branch = b
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = branch?.let { "Rama: $it" } ?: "Control de versiones",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { reload() }) { Text("Actualizar") }
        }

        if (!git.available) {
            Text(
                statusText.ifEmpty { "No hay un repositorio git en este proyecto." },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            TextButton(onClick = {
                scope.launch(Dispatchers.IO) {
                    val r = git.init()
                    withContext(Dispatchers.Main) { statusText = r; reload() }
                }
            }) { Text("Inicializar repositorio") }
        } else if (diff != null) {
            val d = diff!!
            TextButton(onClick = { diff = null }) { Text("← Volver") }
            Text(
                d.path,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                diffText,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
            )
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(files) { f ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            scope.launch(Dispatchers.IO) {
                                val t = runCatching { git.diff(f.path, f.staged) }.getOrDefault("")
                                withContext(Dispatchers.Main) { diff = f; diffText = t }
                            }
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = f.staged,
                            onCheckedChange = {
                                scope.launch(Dispatchers.IO) {
                                    if (f.staged) git.unstage(listOf(f.path)) else git.stage(listOf(f.path))
                                    reload()
                                }
                            },
                        )
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(f.path, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                            Text(
                                statusLabel(f.status, f.staged),
                                color = statusColor(f.status),
                                style = MaterialTheme.typography.labelSmall,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Mensaje de commit") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        git.stage(files.map { it.path })
                        statusText = git.commit(message)
                        message = ""
                        reload()
                    }
                }) { Text("Preparar todo y Commit") }
                TextButton(onClick = {
                    scope.launch(Dispatchers.IO) { statusText = git.push() }
                }) { Text("Push") }
            }

            if (statusText.isNotEmpty()) {
                Text(
                    statusText,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun statusLabel(status: GitStatus, staged: Boolean): String {
    val base = when (status) {
        GitStatus.Added -> "Añadido"
        GitStatus.Modified -> "Modificado"
        GitStatus.Deleted -> "Eliminado"
        GitStatus.Untracked -> "Sin seguimiento"
    }
    return if (staged) "Preparado · $base" else base
}

@Composable
private fun statusColor(status: GitStatus) = when (status) {
    GitStatus.Added -> Ide.colors.gitAdded
    GitStatus.Modified -> Ide.colors.gitModified
    GitStatus.Deleted -> Ide.colors.gitDeleted
    GitStatus.Untracked -> Ide.colors.gitUntracked
}
