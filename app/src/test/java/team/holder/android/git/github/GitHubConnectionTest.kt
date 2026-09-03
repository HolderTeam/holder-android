package team.holder.android.git.github

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import team.holder.android.HolderProject

/** Covers GitHubConnection's pure repo-naming logic (slugify/repoNameFor) in isolation from
 * everything network/Keystore-shaped in the rest of the file, which needs a real device --
 * see ensureProjectRepo's doc comment for the naming scheme these implement, and
 * GITHUB_INTEGRATION_ANDROID_PLAN.md for why uniqueness never depends on the slug itself. */
class GitHubConnectionTest {
    private fun project(name: String, projectId: String = "3fa85f64-5717-4562-b3fc-2c963f66afa6") =
        HolderProject(projectId = projectId, name = name, gitRemoteUrl = null, privacyMode = "plain")

    @Test
    fun slugify_lowercasesAndHyphenatesPunctuationAndSpaces() {
        assertEquals("taylor-swift-s-hair", GitHubConnection.slugify("Taylor Swift's Hair"))
    }

    @Test
    fun slugify_collapsesConsecutiveNonAlphanumericRunsIntoOneHyphen() {
        assertEquals("a-b", GitHubConnection.slugify("a   ---!!!  b"))
    }

    @Test
    fun slugify_trimsLeadingAndTrailingHyphens() {
        assertEquals("hello", GitHubConnection.slugify("  !!! hello !!!  "))
    }

    @Test
    fun slugify_isEmptyForAnAllEmojiName_ratherThanCrashingOrLeavingStrayHyphens() {
        assertEquals("", GitHubConnection.slugify("🦄🌈")) // unicorn, rainbow
    }

    @Test
    fun slugify_truncatesToFortyCharactersWithoutATrailingHyphen() {
        val slug = GitHubConnection.slugify("a".repeat(60))
        assertTrue("expected length <= 40, was ${slug.length}", slug.length <= 40)
        assertTrue(slug.isNotEmpty() && !slug.endsWith("-"))
    }

    @Test
    fun repoNameFor_combinesTheSlugAndTheProjectId() {
        val id = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        assertEquals("holder-taylor-swift-s-hair-$id", GitHubConnection.repoNameFor(project("Taylor Swift's Hair", id)))
    }

    @Test
    fun repoNameFor_fallsBackToNoSlugForAnUnslugifiableName() {
        val id = "3fa85f64-5717-4562-b3fc-2c963f66afa6"
        assertEquals("holder-$id", GitHubConnection.repoNameFor(project("🦄", id)))
    }

    @Test
    fun repoNameFor_isDeterministic_sameProjectAlwaysProducesTheSameName() {
        val a = project("Taylor Swift's Hair")
        val b = project("Taylor Swift's Hair")
        assertEquals(GitHubConnection.repoNameFor(a), GitHubConnection.repoNameFor(b))
    }
}
