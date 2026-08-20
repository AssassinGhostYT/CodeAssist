package dev.ide.core.services

import dev.ide.core.EngineContext
import dev.ide.ui.backend.GitBranch
import dev.ide.ui.backend.GitCommitInfo
import dev.ide.ui.backend.GitFile
import dev.ide.ui.backend.GitOpResult
import dev.ide.ui.backend.GitRemote
import dev.ide.ui.backend.GitService
import dev.ide.ui.backend.GitStash
import dev.ide.ui.backend.GitStatus
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ListBranchCommand.ListMode
import org.eclipse.jgit.api.MergeStatus
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.transport.TrackingRefUpdate
import org.eclipse.jgit.transport.URIish
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Git integration backed by JGit (pure Java, runs on ART — no `git` CLI, which Android doesn't ship). Every
 * operation opens the workspace's repository and degrades to a readable [GitOpResult] failure when the
 * workspace isn't a repo or JGit can't perform the operation, so the UI can show success/error toasts and
 * conflict lists instead of throwing. [init] creates a repo on demand so the Source-control panel is useful
 * even for a freshly opened (non-git) project.
 */
internal class GitServiceCli(private val ctx: EngineContext) : GitService {

    private val root: File get() = ctx.workspaceRoot.toFile()

    override val available: Boolean get() = File(root, ".git").exists()

    private fun open(): Git? = runCatching { Git.open(root) }.getOrNull()

    private fun run(block: Git.() -> GitOpResult, noRepo: String): GitOpResult {
        val git = open() ?: return GitOpResult.fail(noRepo)
        return runCatching { git.block() }.getOrElse {
            GitOpResult.fail("No se pudo completar la operación: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}")
        }
    }

    override fun init(): GitOpResult {
        if (available) return GitOpResult.ok("El repositorio ya está inicializado.")
        return runCatching {
            Git.init().setDirectory(root).call()
            "Repositorio git inicializado en ${root.absolutePath}."
        }.fold(
            onSuccess = { GitOpResult.ok(it) },
            onFailure = { GitOpResult.fail("No se pudo inicializar el repositorio: ${it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "error"}") },
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
            cmd.setPathFilter(org.eclipse.jgit.treewalk.filter.PathFilter.create(path))
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

    override fun commit(message: String): GitOpResult {
        val msg = message.trim()
        if (msg.isEmpty()) return GitOpResult.fail("Escribe un mensaje de commit.")
        return run { git ->
            val commit = git.commit().setMessage(msg).call()
            GitOpResult.ok("Commit ${commit.name.take(7)} creado en ${branch() ?: "la rama actual"}.")
        }
    }

    override fun push(remote: String): GitOpResult {
        return run({ git ->
            val b = branch() ?: return@run GitOpResult.fail("No se pudo determinar la rama actual.")
            git.push().setRemote(remote).call()
            GitOpResult.ok("Push a $remote/$b completado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun pull(remote: String): GitOpResult {
        return run({ git ->
            val result = git.pull().setRemote(remote).setRebase(false).call()
            val mergeResult = result.mergeResult
            val conflicts = mergeResult?.conflicts?.keys?.toList().orEmpty()
            when {
                result.isSuccessful -> GitOpResult.ok("Pull completado desde $remote.")
                conflicts.isNotEmpty() -> GitOpResult(false, "Pull completado con conflictos.", conflicts)
                else -> GitOpResult.fail("No se pudo hacer pull desde $remote: revisa la conexión o los cambios locales pendientes.")
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun fetch(remote: String): GitOpResult {
        return run({ git ->
            val result = git.fetch().setRemote(remote).call()
            val updated = result.trackingRefUpdates.filter {
                it.result == TrackingRefUpdate.Result.NEW ||
                    it.result == TrackingRefUpdate.Result.FAST_FORWARD ||
                    it.result == TrackingRefUpdate.Result.FORCED
            }
            if (updated.isEmpty()) GitOpResult.ok("Fetch completado: sin cambios nuevos en $remote.")
            else GitOpResult.ok("Fetch completado: ${updated.size} rama(s) actualizada(s) desde $remote.")
        }, "Git no está disponible en este entorno.")
    }

    override fun merge(branch: String): GitOpResult {
        return run({ git ->
            val ref = if (git.repository.findRef("refs/heads/$branch") != null) "refs/heads/$branch"
            else "refs/remotes/$branch"
            val result = git.merge().include(ref).call()
            when (result.mergeStatus) {
                MergeStatus.CONFLICTING -> GitOpResult(
                    success = false,
                    message = "Conflicto al fusionar $branch con ${branch() ?: "la rama actual"}.",
                    conflicts = result.conflicts?.keys?.toList().orEmpty(),
                )
                MergeStatus.ALREADY_UP_TO_DATE -> GitOpResult.ok("Ya está actualizada con $branch.")
                else -> if (result.mergeStatus.isSuccessful)
                    GitOpResult.ok("Rama $branch fusionada en ${branch() ?: "la rama actual"}.")
                else GitOpResult.fail("No se pudo fusionar $branch: ${result.mergeStatus}")
            }
        }, "Git no está disponible en este entorno.")
    }

    override fun branches(): List<GitBranch> {
        val git = open() ?: return emptyList()
        return runCatching {
            val current = branch()
            git.branchList().setListMode(ListMode.ALL).call().map { ref ->
                val remote = ref.name.startsWith(Constants.R_REMOTES)
                val name = if (remote) ref.name.substring(Constants.R_REMOTES.length)
                else ref.name.substring(Constants.R_HEADS.length)
                GitBranch(name = name, isCurrent = !remote && name == current, isRemote = remote)
            }
        }.getOrDefault(emptyList())
    }

    override fun createBranch(name: String): GitOpResult {
        val clean = name.trim()
        if (clean.isEmpty()) return GitOpResult.fail("Escribe un nombre para la rama.")
        return run({ git ->
            val existing = git.repository.findRef("refs/heads/$clean")
            if (existing != null) return@run GitOpResult.fail("La rama $clean ya existe.")
            git.branchCreate().setName(clean).call()
            GitOpResult.ok("Rama $clean creada correctamente.")
        }, "Git no está disponible en este entorno.")
    }

    override fun checkoutBranch(name: String): GitOpResult {
        val clean = name.trim()
        if (clean.isEmpty()) return GitOpResult.fail("Elige una rama.")
        return run({ git ->
            val remote = clean.startsWith("origin/") || clean.contains('/')
            if (!remote) {
                git.checkout().setName(clean).call()
            } else {
                val local = clean.substringAfter('/')
                git.checkout()
                    .setCreateBranch(true)
                    .setName(local)
                    .setUpstreamMode(SetupUpstreamMode.TRACK)
                    .setStartPoint(clean)
                    .call()
            }
            GitOpResult.ok("Cambiado a la rama ${if (remote) clean.substringAfter('/') else clean}.")
        }, "Git no está disponible en este entorno.")
    }

    override fun deleteBranch(name: String): GitOpResult {
        return run({ git ->
            git.branchDelete().setBranchNames(name).setForce(true).call()
            GitOpResult.ok("Rama $name eliminada.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashSave(message: String): GitOpResult {
        return run({ git ->
            val commit = if (message.isBlank()) git.stashCreate().call()
            else git.stashCreate().setWorkingDirectoryMessage(message.trim()).call()
            if (commit == null) GitOpResult.fail("No hay cambios para guardar en el stash.")
            else GitOpResult.ok("Cambios guardados en el stash.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashList(): List<GitStash> {
        val git = open() ?: return emptyList()
        return runCatching {
            git.stashList().call()
                .sortedByDescending { it.commitTime }
                .mapIndexed { index, commit ->
                    GitStash(id = index, message = commit.shortMessage)
                }
        }.getOrDefault(emptyList())
    }

    override fun stashRestore(stashId: Int): GitOpResult {
        return run({ git ->
            val commit = git.stashList().call()
                .sortedByDescending { it.commitTime }
                .getOrNull(stashId)
            if (commit == null) return@run GitOpResult.fail("Stash no encontrado.")
            git.stashApply().setStashRef(commit.name).call()
            GitOpResult.ok("Cambios del stash restaurados.")
        }, "Git no está disponible en este entorno.")
    }

    override fun stashDrop(stashId: Int): GitOpResult {
        return run({ git ->
            git.stashDrop().setStashRef(stashId).call()
            GitOpResult.ok("Stash eliminado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun remotes(): List<GitRemote> {
        val git = open() ?: return emptyList()
        return runCatching {
            git.remoteList().call().map { remote ->
                val url = remote.uris.firstOrNull()?.toString().orEmpty()
                GitRemote(name = remote.name, url = url)
            }
        }.getOrDefault(emptyList())
    }

    override fun addRemote(name: String, url: String): GitOpResult {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty()) return GitOpResult.fail("Escribe un nombre para el remoto.")
        if (cleanUrl.isEmpty()) return GitOpResult.fail("Escribe la URL del repositorio remoto.")
        return run({ git ->
            git.remoteAdd().setName(cleanName).setUri(URIish(cleanUrl)).call()
            GitOpResult.ok("Repositorio remoto $cleanName conectado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun removeRemote(name: String): GitOpResult {
        return run({ git ->
            git.remoteRemove().setName(name).call()
            GitOpResult.ok("Repositorio remoto $name desconectado.")
        }, "Git no está disponible en este entorno.")
    }

    override fun lastCommit(): GitCommitInfo? {
        val git = open() ?: return null
        return runCatching {
            val commit = git.log().setMaxCount(1).call().firstOrNull() ?: return null
            GitCommitInfo(
                shortHash = commit.name.take(7),
                message = commit.shortMessage,
                author = commit.authorIdent.name,
                dateMillis = commit.commitTime * 1000L,
            )
        }.getOrNull()
    }

    override fun refresh() {}
}