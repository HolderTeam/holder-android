package team.holder.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
    fun markdownViewer_resourceImageReference_fallsBackToAnErrorRatherThanCrashing() {
        // No HolderNative.initialize() in this isolated test -- HolderNative.getResource
        // throws deterministically, exercising rememberResourceAttachmentKind's failure path
        // (and proving a resource-image paragraph doesn't take down the rest of the document
        // with it). The failure happens before the dispatch even knows whether this would
        // have been an image or not, hence "attachment", not "image", in the message.
        composeRule.setContent {
            HolderMarkdownViewer(
                markdown = "Before.\n\n![Holiday photo](holder://resource/some-id)\n\nAfter.",
                projectId = "project-1",
                cardId = null,
                onNavigateToCard = { _, _ -> },
                onNavigateToTag = {},
            )
        }

        composeRule.onNodeWithText("Before.").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Couldn't load attachment", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("After.").assertIsDisplayed()
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
            SettingsScreen(onBack = {}, onRestoreBackupClick = {})
        }

        // Not asserting Connect/Disconnect specifically -- Google Drive's connected state is
        // real local DataStore state that can carry over between test runs on the same
        // device. The point of this test is that the whole screen, including the new Google
        // Drive row, composes and renders without throwing.
        composeRule.onNodeWithText("Google Drive").assertIsDisplayed()
        // performScrollTo() first: on a shorter screen (the CI managed devices' Pixel 2
        // profile, unlike a local emulator with more vertical space) this row is below the
        // fold by default -- scrolling to it is also a real regression check that the screen
        // is genuinely scrollable, not just that this text exists somewhere in the tree.
        composeRule.onNodeWithText("Background git sync").performScrollTo().assertIsDisplayed()
    }
}
