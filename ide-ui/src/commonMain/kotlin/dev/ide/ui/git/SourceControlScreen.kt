package dev.ide.ui.git

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.components.Chip
import dev.ide.ui.components.pressScale
import dev.ide.ui.icons.CaIcons
import dev.ide.ui.theme.Ca
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class GitChangeType { MODIFIED, ADDED, DELETED, UNTRACKED }

data class GitFileChange(val path: String, val type: GitChangeType)

/**
 * Native Source Control UI panel with GitHub OAuth & Token authentication, staging, commit & push capabilities.
 */
@Composable
fun SourceControlScreen(
    backend: IdeBackend,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val projectRoot = backend.project.rootPath

    var token by remember { mutableStateOf(backend.settings.preference("github_token").orEmpty()) }
    var showTokenInput by remember { mutableStateOf(token.isBlank()) }

    var commitMessage by remember { mutableStateOf("") }
    var changes by remember { mutableStateOf<List<GitFileChange>>(emptyList()) }
    var currentBranch by remember { mutableStateOf("main") }
    var isBusy by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    fun refreshStatus() {
        if (projectRoot.isBlank()) return
        scope.launch(Dispatchers.IO) {
            isBusy = true
            try {
                val dir = File(projectRoot)
                val branchProc = ProcessBuilder("git", "rev-parse", "--abbrev-ref", "HEAD").directory(dir).start()
                val branch = branchProc.inputStream.bufferedReader().readLine()?.trim().orEmpty()
                if (branch.isNotBlank()) currentBranch = branch

                val statusProc = ProcessBuilder("git", "status", "--porcelain").directory(dir).start()
                val lines = statusProc.inputStream.bufferedReader().readLines()
                val parsed = lines.mapNotNull { line ->
                    if (line.length < 4) return@mapNotNull null
                    val statusStr = line.substring(0, 2)
                    val filePath = line.substring(3).trim()
                    val changeType = when {
                        statusStr.contains("M") -> GitChangeType.MODIFIED
                        statusStr.contains("A") || statusStr.contains("?") -> GitChangeType.ADDED
                        statusStr.contains("D") -> GitChangeType.DELETED
                        else -> GitChangeType.MODIFIED
                    }
                    GitFileChange(filePath, changeType)
                }
                withContext(Dispatchers.Main) {
                    changes = parsed
                }
            } catch (_: Exception) {
            } finally {
                withContext(Dispatchers.Main) { isBusy = false }
            }
        }
    }

    LaunchedEffect(projectRoot) {
        refreshStatus()
    }

    Column(modifier.fillMaxSize().background(Ca.colors.bg).padding(16.dp)) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(CaIcons.gitBranch, contentDescription = null, modifier = Modifier.size(20.dp), tint = Ca.colors.accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Source Control ($currentBranch)",
                    color = Ca.colors.textPrimary,
                    style = Ca.type.title3,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row {
                Icon(
                    CaIcons.refresh,
                    contentDescription = "Refresh",
                    modifier = Modifier.size(20.dp).clickable { refreshStatus() },
                    tint = Ca.colors.textSecondary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    CaIcons.settings,
                    contentDescription = "GitHub Auth Settings",
                    modifier = Modifier.size(20.dp).clickable { showTokenInput = !showTokenInput },
                    tint = if (token.isNotBlank()) Ca.colors.accent else Ca.colors.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // OAuth / Token Section
        if (showTokenInput) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md))
                    .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.md))
                    .padding(12.dp)
            ) {
                Text(
                    "GitHub Authentication (OAuth / Personal Access Token)",
                    color = Ca.colors.textPrimary,
                    style = Ca.type.subhead,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                BasicTextField(
                    value = token,
                    onValueChange = { token = it },
                    singleLine = true,
                    textStyle = Ca.type.footnote.copy(color = Ca.colors.textPrimary),
                    cursorBrush = SolidColor(Ca.colors.accent),
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ca.colors.bg, RoundedCornerShape(Ca.radius.sm))
                        .padding(8.dp),
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(
                        modifier = Modifier
                            .pressScale()
                            .background(Ca.colors.accent, RoundedCornerShape(Ca.radius.sm))
                            .clickable {
                                backend.settings.setPreference("github_token", token.trim())
                                showTokenInput = false
                                statusMessage = "GitHub token saved permanently."
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("Save Credential", color = Ca.colors.bg, style = Ca.type.footnote, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Commit Message Input
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.md))
                .border(1.dp, Ca.colors.hairline, RoundedCornerShape(Ca.radius.md))
                .padding(10.dp)
        ) {
            BasicTextField(
                value = commitMessage,
                onValueChange = { commitMessage = it },
                textStyle = Ca.type.subhead.copy(color = Ca.colors.textPrimary),
                cursorBrush = SolidColor(Ca.colors.accent),
                modifier = Modifier.fillMaxWidth().height(48.dp),
                decorationBox = { innerTextField ->
                    if (commitMessage.isEmpty()) {
                        Text("Message (Ctrl+Enter to commit)", color = Ca.colors.textTertiary, style = Ca.type.subhead)
                    }
                    innerTextField()
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Commit & Push Button
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .pressScale()
                        .background(if (commitMessage.isNotBlank() && !isBusy) Ca.colors.accent else Ca.colors.surface3, RoundedCornerShape(Ca.radius.sm))
                        .clickable(enabled = commitMessage.isNotBlank() && !isBusy) {
                            scope.launch(Dispatchers.IO) {
                                isBusy = true
                                statusMessage = "Committing and pushing..."
                                try {
                                    val dir = File(projectRoot)
                                    ProcessBuilder("git", "add", ".").directory(dir).start().waitFor()
                                    ProcessBuilder("git", "commit", "-m", commitMessage.trim()).directory(dir).start().waitFor()

                                    val pushProc = if (token.isNotBlank()) {
                                        val remoteUrl = ProcessBuilder("git", "config", "--get", "remote.origin.url").directory(dir).start().inputStream.bufferedReader().readLine().orEmpty()
                                        val authedUrl = if (remoteUrl.startsWith("https://")) {
                                            "https://$token@" + remoteUrl.removePrefix("https://")
                                        } else remoteUrl
                                        ProcessBuilder("git", "push", authedUrl, currentBranch).directory(dir).start()
                                    } else {
                                        ProcessBuilder("git", "push", "origin", currentBranch).directory(dir).start()
                                    }

                                    val exit = pushProc.waitFor()
                                    withContext(Dispatchers.Main) {
                                        if (exit == 0) {
                                            statusMessage = "Push succeeded!"
                                            commitMessage = ""
                                            refreshStatus()
                                        } else {
                                            val err = pushProc.errorStream.bufferedReader().readText()
                                            statusMessage = "Push failed: $err"
                                        }
                                    }
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) { statusMessage = "Error: ${e.message}" }
                                } finally {
                                    withContext(Dispatchers.Main) { isBusy = false }
                                }
                            }
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Ca.colors.textPrimary, strokeWidth = 2.dp)
                    } else {
                        Text("Commit & Push", color = Ca.colors.bg, style = Ca.type.subhead, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(msg, color = Ca.colors.accent, style = Ca.type.footnote)
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Changes List Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CHANGES (${changes.size})", color = Ca.colors.textSecondary, style = Ca.type.caption, fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Changes List
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(changes) { change ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Ca.colors.surface2, RoundedCornerShape(Ca.radius.sm))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        change.path,
                        color = Ca.colors.textPrimary,
                        style = Ca.type.footnote,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    val (label, bg, textColor) = when (change.type) {
                        GitChangeType.MODIFIED -> Triple("M", Ca.colors.surface3, Ca.colors.accent)
                        GitChangeType.ADDED, GitChangeType.UNTRACKED -> Triple("A", Ca.colors.surface3, Ca.colors.syntax.string)
                        GitChangeType.DELETED -> Triple("D", Ca.colors.surface3, Ca.colors.syntax.keyword)
                    }
                    Chip(text = label, fill = bg, textColor = textColor)
                }
            }
        }
    }
}
