package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Test
import team.holder.android.HolderProject

/** Covers eligibleProjects in isolation -- checkAndMarkOfferedOnce/backfill need a real
 * Context/HolderNative/network and aren't unit-testable without more infra, matching this
 * codebase's existing split between pure-logic and device-only coverage. */
class GitHubBackfillTest {
    private fun project(name: String, remoteUrl: String? = null) =
        HolderProject(projectId = name, name = name, gitRemoteUrl = remoteUrl, privacyMode = "encrypted_git")

    @Test
    fun eligibleProjects_includesOnlyProjectsWithNoRemoteAtAll() {
        val projects = listOf(
            project("House"),
            project("Car", remoteUrl = "git@github.com:zeth/holder-car.git"),
            project("Easter"),
        )

        assertEquals(listOf(project("House"), project("Easter")), GitHubBackfill.eligibleProjects(projects))
    }

    @Test
    fun eligibleProjects_excludesAProjectAlreadySyncedToANonGitHubHost() {
        // Not just "no GitHub remote" -- no remote at all. A project already pointed at
        // GitLab (or anywhere else) already has an explicit destination, so it's not
        // "unsynced" -- see GITHUB_INTEGRATION_ANDROID_PLAN.md's eligibility rule.
        val projects = listOf(project("Youtube", remoteUrl = "git@gitlab.com:zeth/holder-youtube.git"))

        assertEquals(emptyList<HolderProject>(), GitHubBackfill.eligibleProjects(projects))
    }

    @Test
    fun eligibleProjects_returnsNothingWhenEveryProjectAlreadyHasARemote() {
        val projects = listOf(project("House", remoteUrl = "git@github.com:zeth/holder-house.git"))

        assertEquals(emptyList<HolderProject>(), GitHubBackfill.eligibleProjects(projects))
    }

    @Test
    fun eligibleProjects_returnsEverythingWhenNoProjectHasARemote() {
        val projects = listOf(project("House"), project("Car"))

        assertEquals(projects, GitHubBackfill.eligibleProjects(projects))
    }
}
