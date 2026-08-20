package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.ui.backend.GitFile
import dev.ide.ui.backend.GitService
import dev.ide.ui.backend.GitStatus
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Git integration backed by JGit (pure Java, runs on ART — no `git` CLI, which Android doesn't ship). Every
 * operation opens the workspace's repository and degrades to empty/no-op results when the workspace isn't a
 * repo or JGit can't read it, so the UI can show a message instead of throwing. [init] creates a repo on
 * demand so the Source-control panel is useful even for a freshly opened (non-git) project.
 */
internal class GitServiceCli(private val ctx: EngineContext) : GitService {

    private val root: File get() = ctx.workspaceRoot.toFile()

    private fun open(): Git? = runCatching { Git.open(root) }.getOrNull()

    override val available: Boolean get() = open() != null

    override fun init(): String {
        if (available) return "El repositorio ya está inicializado."
        return runCatching {
            Git.init().setDirectory(root).call()
            "Repositorio git inicializado en ${root.absolutePath}."
        }.fold(
            onSuccess = { it },
            onFailure = { "No se pudo inicializar el repositorio: ${it.message?.lineSequence()?.firstOrNull() ?: "error"}" },
        )
    }

    override fun branch(): String? {
        val git = open() ?: return null
        return runCatching {
            val full = git.repository.fullBranch
            if (full.startsWith(Constants.R_HEADS)) full.substring(Constants.R_HEADS.length) else null
        }.getOrNull()
    }

    override fun status(): List<GitFile> {
        val git = open() ?: return emptyList()
        return runCatching {
            val st = git.status().call()
            val out = mutableListOf<GitFile>()
            st.added.forEach { out += GitFile(it, GitStatus.Added, staged = true) }
            st.changed.forEach { out += GitFile(it, GitStatus.Modified, staged = true) }
            st.removed.forEach { out += GitFile(it, GitStatus.Deleted, staged = true) }
            st.modified.forEach { out += GitFile(it, GitStatus.Modified, staged = false) }
            st.missing.forEach { out += GitFile(it, GitStatus.Deleted, staged = false) }
            st.untracked.forEach { out += GitFile(it, GitStatus.Untracked, staged = false) }
            st.untrackedFolders.forEach { out += GitFile(it, GitStatus.Untracked, staged = false) }
            out
        }.getOrDefault(emptyList())
    }

    override fun diff(path: String, staged: Boolean): String {
        val git = open() ?: return ""
        return runCatching {
            val cmd = git.diff()
            if (staged) cmd.setCached(true)
            cmd.setPathFilter(PathFilter.create(path))
            val entries: List<DiffEntry> = cmd.call()
            val baos = ByteArrayOutputStream()
            DiffFormatter(baos).apply {
                setRepository(git.repository)
                format(entries)
                flush()
            }
            baos.toString("UTF-8")
        }.getOrDefault("")
    }

    override fun stage(paths: List<String>) {
        val git = open() ?: return
        if (paths.isEmpty()) return
        runCatching {
            val add = git.add()
            paths.forEach { add.addFilepattern(it) }
            add.call()
        }
    }

    override fun unstage(paths: List<String>) {
        val git = open() ?: return
        if (paths.isEmpty()) return
        runCatching {
            val reset = git.reset()
            paths.forEach { reset.addPath(it) }
            reset.call()
        }
    }

    override fun commit(message: String): String {
        val msg = message.trim()
        if (msg.isEmpty()) return "Escribe un mensaje de commit."
        val git = open() ?: return "Git no está disponible en este entorno."
        return runCatching { git.commit().setMessage(msg).call() }.fold(
            onSuccess = { "Commit ${it.name.take(7)} creado en ${branch() ?: "la rama actual"}." },
            onFailure = { "No se pudo hacer commit: ${it.message?.lineSequence()?.firstOrNull()?.take(80) ?: "error"}" },
        )
    }

    override fun push(remote: String): String {
        val git = open() ?: return "Git no está disponible en este entorno."
        val b = branch() ?: return "No se pudo determinar la rama actual."
        return runCatching { git.push().setRemote(remote).call() }.fold(
            onSuccess = { "Push a $remote/$b completado." },
            onFailure = { "No se pudo hacer push: ${it.message?.lineSequence()?.firstOrNull()?.take(80) ?: "error"}" },
        )
    }

    override fun remotes(): List<String> {
        val git = open() ?: return emptyList()
        return runCatching { git.remoteList().call().map { it.name } }.getOrDefault(emptyList())
    }

    override fun refresh() {}
}
