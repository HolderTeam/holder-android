package team.holder.android

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
        fields[1].performTextInput(initialBody)
        composeRule.onNodeWithContentDescription("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(initialBody).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithContentDescription("Edit").performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size >= 2
        }

        composeRule
            .onNode(hasSetTextAction() and hasText(initialBody))
            .performTextInput("\nEdited and persisted.")
        composeRule.onNodeWithContentDescription("Save").performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Edited and persisted.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        composeRule.onNodeWithContentDescription("Back").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).performClick()

        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithText("Edited and persisted.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertTrue(
            composeRule.onAllNodesWithText("Edited and persisted.", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
    }
}
