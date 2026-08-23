package team.holder.android

import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Milestone creation through the real Activity, Compose navigation, JNI, and libholder, then
 * confirms the Calendar screen actually picks it up: create a card, add a milestone from its
 * About screen, then open the project Calendar and confirm the milestone shows there with the
 * right card title -- this is the seam (range query -> JSON -> cardTitle -> Compose list) most
 * likely to regress silently, since it's normally only checked by hand on the emulator. */
@RunWith(AndroidJUnit4::class)
class MilestoneCalendarSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

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
    fun addMilestone_showsUpOnTheCalendarWithTheCardTitle() {
        val title = "Milestone smoke card ${UUID.randomUUID()}"
        smokeTitle = title

        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Home").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Home").performClick()

        composeRule.onNodeWithContentDescription("New card").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }
        val fields = composeRule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput(title)
        fields[1].performTextInput("Created by milestone smoke test.")
        composeRule.onNodeWithContentDescription("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).performClick()

        composeRule.onNodeWithContentDescription("Connections").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("About this card").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Add milestone").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasText("Add milestone") and hasClickAction())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Defaults (today, all-day, no end) already make the form saveable -- no input needed.
        composeRule.onNode(hasText("Add milestone") and hasClickAction()).performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Milestones").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Connections").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithContentDescription("Calendar").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Calendar").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty())
    }
}
