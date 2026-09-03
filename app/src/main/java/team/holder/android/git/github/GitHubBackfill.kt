package team.holder.android.git.github

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import team.holder.android.HolderNative
import team.holder.android.HolderProject
import team.holder.android.HolderSettings

/** One project's result from a [GitHubBackfill.backfill] run. [GitHubFailure] and
 * [LocalFailure] stay separate (rather than one generic failure carrying a string) so the
 * UI layer can reuse [team.holder.android.ui.githubErrorMessage] for the GitHub half instead
 * of duplicating its copy -- keeping this file free of UI-facing text, same as
 * [GitHubConnection] itself. */
sealed interface BackfillOutcome {
    data object Success : BackfillOutcome
    data class GitHubFailure(val error: GitHubError) : BackfillOutcome
    data class LocalFailure(val message: String) : BackfillOutcome
}

data class BackfillResult(val project: HolderProject, val outcome: BackfillOutcome)

/**
 * The one-time "keep your existing projects safe and synced with GitHub?" offer -- see
 * GITHUB_INTEGRATION_ANDROID_PLAN.md's "Future work: back-filling pre-existing local-only
 * projects" for the full design (why this is the highest-value not-yet-built flow for a
 * non-technical Android-first user, the eligibility rule, why execution is sequential).
 * UI-agnostic, same split as [GitHubConnection] -- a caller (Settings, `RecoverProjectScreen`)
 * drives this and renders `team.holder.android.ui.GitHubBackfillDialog` around it.
 */
object GitHubBackfill {
    /** Projects with no remote at all -- not "no GitHub remote." A project already synced
     * to GitLab/Bitbucket/self-hosted is excluded for free (it's not "unsynced," it already
     * has an explicit destination), and a project someone deliberately marked "keep this on
     * device only" can never appear here either, structurally: that choice only exists once
     * already `Connected`, and this whole offer only ever fires once, at first-connect --
     * such a project can only be created after this offer has already run. */
    fun eligibleProjects(allProjects: List<HolderProject>): List<HolderProject> =
        allProjects.filter { it.gitRemoteUrl == null }

    /** Call once, right after any [GitHubConnection.connect] (or a later `status()` recheck
     * that itself reaches [GitHubStatus.Connected], e.g. after finishing installation
     * separately) reports [GitHubStatus.Connected] for the first time -- from whichever
     * screen triggered it, Settings or Recovery alike; both are expected to call this the
     * same way rather than each tracking their own copy of "has this been offered yet."
     * Returns the projects to offer, or empty if the one-time flag was already set or there's
     * nothing eligible. Marks the flag as a side effect of calling this, unconditionally --
     * whether or not anything was eligible, whether or not the caller ends up showing
     * anything -- so it can never re-fire, matching the "one-time, not an ongoing
     * detect-drift feature" design. */
    suspend fun checkAndMarkOfferedOnce(context: Context): List<HolderProject> {
        val appContext = context.applicationContext
        if (HolderSettings.githubBackfillOfferShown(appContext).first()) return emptyList()
        HolderSettings.setGithubBackfillOfferShown(appContext, true)
        val allProjects = runCatching {
            withContext(Dispatchers.IO) { HolderNative.listProjects() }
        }.getOrElse { emptyList() }
        return eligibleProjects(allProjects)
    }

    /** Runs [GitHubConnection.ensureProjectRepo] + `HolderNative.updateProjectGitRemote` for
     * each of [projects], **one at a time, never in parallel** -- concurrent
     * [GitHubConnection] calls would race reading/writing the same stored refresh token (see
     * the plan's Storage section on refresh-token rotation). [onProgress] is called before
     * each project starts, so a caller can render "Syncing 2 of 5: House...". One project
     * failing (rate limited, `RepositoryNotAccessible`, network) doesn't stop the rest --
     * every project gets its own independent attempt and result. */
    suspend fun backfill(
        context: Context,
        projects: List<HolderProject>,
        onProgress: suspend (index: Int, total: Int, project: HolderProject) -> Unit,
    ): List<BackfillResult> {
        val appContext = context.applicationContext
        return projects.mapIndexed { index, project ->
            onProgress(index, projects.size, project)
            BackfillResult(project, backfillOne(appContext, project))
        }
    }

    private suspend fun backfillOne(context: Context, project: HolderProject): BackfillOutcome =
        when (val repoResult = GitHubConnection.ensureProjectRepo(context, project)) {
            is GitHubResult.Failure -> BackfillOutcome.GitHubFailure(repoResult.error)
            is GitHubResult.Success -> runCatching {
                withContext(Dispatchers.IO) {
                    HolderNative.updateProjectGitRemote(project.projectId, repoResult.value)
                }
            }.fold(
                onSuccess = { BackfillOutcome.Success },
                onFailure = { BackfillOutcome.LocalFailure(it.message ?: it::class.java.simpleName) },
            )
        }
}
