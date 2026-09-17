package team.holder.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Critical user journey through the real Activity, Compose navigation, JNI, and libholder. */
@RunWith(AndroidJUnit4::class)
class WholeAppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    // Deliberately generous: this test's slow steps each wait on a real libholder round trip
    // (git-backed create/save) rendered by a software-GPU emulator under CI contention. The old
    // 20s budget was where this test's flakiness lived -- it would time out mid-flow on a loaded
    // runner and pass on a rerun. 60s covered that, until pixel2Api28 had to move from the aosp
    // to the google system image (aosp no longer ships an x86_64 image for API 28 at all) --
    // the extra weight of Google Play Services on an already CPU-starved software-rendered
    // emulator pushed ordinary steps past 60s too, each run timing out at a different point in
    // the flow rather than hanging at the same spot twice. A correct app still settles well
    // within this; only a genuinely stuck one now waits the full two minutes.
    private val settleTimeoutMs = 120_000L

    private var smokeTitle: String? = null

    @After
    fun tearDown() {
        runCatching {
            val title = smokeTitle ?: return@runCatching
            val home = HolderNative.listProjects().firstOrNull { it.name == "Home" } ?: return@runCatching
            HolderNative.listCards(home.projectId)
                .filter { it.title == title }
                .forEach { HolderNative.deleteCard(it.cardId) }
        }
        HolderNative.close()
    }

    @Test
    fun launchHome_createEditSaveAndReopenCard() {
        val title = "Smoke card ${UUID.randomUUID()}"
        smokeTitle = title
        val initialBody = "Created by whole-app smoke test."

        awaitText("Home")
        composeRule.onNodeWithText("Home").performClick()

        awaitContentDescription("New card")
        composeRule.onNodeWithContentDescription("New card").performClick()
        awaitTextFields()

        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(title)
        fields[1].performTextInput(initialBody)
        awaitContentDescription("Save")
        composeRule.onNodeWithContentDescription("Save").performClick()

        awaitText(title)
        composeRule.onNodeWithText(title).performClick()
        awaitText(initialBody)
        awaitContentDescription("Edit")
        composeRule.onNodeWithContentDescription("Edit").performClick()
        awaitTextFields()

        composeRule
            .onNode(hasSetTextAction() and hasText(initialBody))
            .performTextInput("\nEdited and persisted.")
        awaitContentDescription("Save")
        composeRule.onNodeWithContentDescription("Save").performClick()

        awaitText("Edited and persisted.", substring = true)

        // Click the first "Back" (the card view's own TopAppBar arrow), not "the" one: the card
        // sits in a HorizontalPager of its siblings (see CardViewPagerScreen), and more than one
        // sibling page -- each its own CardViewScreen with its own TopAppBar Back arrow -- can be
        // composed at once. They all invoke the same onBack. An earlier "exactly one" wait here
        // predated that pager and was a source of this test's CI flakiness.
        composeRule.waitUntil(timeoutMillis = settleTimeoutMs) {
            composeRule.onAllNodesWithContentDescription("Back").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithContentDescription("Back").onFirst().performClick()

        awaitText(title)
        composeRule.onNodeWithText(title).performClick()

        awaitText("Edited and persisted.", substring = true)
        assertTrue(
            composeRule.onAllNodesWithText("Edited and persisted.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }

    private fun awaitText(text: String, substring: Boolean = false) =
        composeRule.waitUntil(timeoutMillis = settleTimeoutMs) {
            composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitContentDescription(description: String) =
        composeRule.waitUntil(timeoutMillis = settleTimeoutMs) {
            composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty()
        }

    private fun awaitTextFields() = composeRule.waitUntil(timeoutMillis = settleTimeoutMs) {
        composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
    }
}
