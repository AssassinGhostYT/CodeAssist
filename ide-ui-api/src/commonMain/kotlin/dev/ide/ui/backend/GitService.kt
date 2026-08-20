package dev.ide.ui.backend

/**
 * Source-control integration surfaced by the Source-control panel. Operations run against the workspace's
 * git repository (via the `git` CLI on the host). [NoopGitService] is the default so backends that don't
 * wire a real implementation keep compiling and degrade gracefully.
 */
data class GitFile(
    val path: String,
    val status: GitStatus,
    /** True when the change is staged in the index (prepared for the next commit). */
    val staged: Boolean,
)

interface GitService {
    /** False when `git` is missing or the workspace isn't inside a repository. */
    val available: Boolean

    /** Current branch name, or null when unavailable. */
    fun branch(): String?

    /** Working-tree changes, split into staged (prepared) and unstaged entries. */
    fun status(): List<GitFile>

    /** Unified diff for [path]; [staged] selects the index (prepared) diff. */
    fun diff(path: String, staged: Boolean): String

    fun stage(paths: List<String>)
    fun unstage(paths: List<String>)

    /** Create a commit with [message]; returns a short human-readable result. */
    fun commit(message: String): String

    /** Push the current branch to [remote]; returns a short human-readable result. */
    fun push(remote: String = "origin"): String

    /** Configured remotes (e.g. `["origin"]`). */
    fun remotes(): List<String>

    /** Initialize a git repository at the workspace root (no-op if one already exists). */
    fun init(): String

    /** Drop any cached state (called after operations that change the tree). */
    fun refresh()
}

object NoopGitService : GitService {
    override val available: Boolean get() = false
    override fun branch(): String? = null
    override fun status(): List<GitFile> = emptyList()
    override fun diff(path: String, staged: Boolean): String = ""
    override fun stage(paths: List<String>) {}
    override fun unstage(paths: List<String>) {}
    override fun commit(message: String): String = "Git no está disponible en este entorno."
    override fun push(remote: String): String = "Git no está disponible en este entorno."
    override fun remotes(): List<String> = emptyList()
    override fun init(): String = "Git no está disponible en este entorno."
    override fun refresh() {}
}
