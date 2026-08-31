package team.holder.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.holder.android.ui.markdown.HolderMarkdownViewer
import team.holder.android.ui.screens.CardEditScreen
import team.holder.android.ui.screens.SettingsScreen

@RunWith(AndroidJUnit4::class)
class ComposeUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun markdownViewer_rendersHeadingsListsAndWikilinkLabels() {
        composeRule.setContent {
            HolderMarkdownViewer(
                markdown = "# Heading\n\nA paragraph.\n\n- First item\n- Second item\n\n[[Another card]]",
                projectId = "project-1",
                cardId = null,
                onNavigateToCard = { _, _ -> },
                onNavigateToTag = {},
            )
        }

        composeRule.onNodeWithText("Heading").assertIsDisplayed()
        composeRule.onNodeWithText("A paragraph.").assertIsDisplayed()
        composeRule.onNodeWithText("First item").assertIsDisplayed()
        composeRule.onNodeWithText("Second item").assertIsDisplayed()
        composeRule.onNodeWithText("Another card").assertIsDisplayed()
    }

    @Test
    fun cardEdit_cancelWithoutChanges_callsCancelImmediately() {
        var cancelCount = 0

        composeRule.setContent {
            CardEditScreen(
                screenTitle = "Edit card",
                initialTitle = "Original",
                initialContent = "# Original\n\nBody",
                saving = false,
                onSave = { _, _ -> },
                onCancel = { cancelCount++ },
            )
        }

        composeRule.onNodeWithContentDescription("Cancel").performClick()

        assertEquals(1, cancelCount)
    }

    @Test
    fun cardEdit_dirtyCancel_canKeepEditingOrDiscard() {
        var cancelCount = 0

        composeRule.setContent {
            CardEditScreen(
                screenTitle = "Edit card",
                initialTitle = "Original",
                initialContent = "# Original\n\nBody",
                saving = false,
                onSave = { _, _ -> },
                onCancel = { cancelCount++ },
            )
        }

        composeRule
            .onNode(hasSetTextAction() and androidx.compose.ui.test.hasText("Original"))
            .performTextReplacement("Changed")
        composeRule.onNodeWithContentDescription("Cancel").performClick()

        composeRule.onNodeWithText("Discard changes?").assertIsDisplayed()
        composeRule.onNodeWithText("Keep editing").performClick()
        composeRule.onNodeWithText("Discard changes?").assertDoesNotExist()
        assertEquals(0, cancelCount)

        composeRule.onNodeWithContentDescription("Cancel").performClick()
        composeRule.onNodeWithText("Discard").performClick()

        assertEquals(1, cancelCount)
    }

    @Test
    fun settingsScreen_rendersWithoutCrashing() {
        composeRule.setContent {
            SettingsScreen(onBack = {})
        }

        // Not asserting Connect/Disconnect specifically -- Google Drive's connected state is
        // real local DataStore state that can carry over between test runs on the same
        // device. The point of this test is that the whole screen, including the new Google
        // Drive row, composes and renders without throwing.
        composeRule.onNodeWithText("Google Drive").assertIsDisplayed()
        composeRule.onNodeWithText("Background git sync").assertIsDisplayed()
    }
}
