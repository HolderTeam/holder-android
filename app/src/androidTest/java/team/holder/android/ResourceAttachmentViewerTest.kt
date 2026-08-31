package team.holder.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.holder.android.ui.markdown.HolderMarkdownViewer

/**
 * Non-image resources need a real imported Asset to render against -- HolderMarkdownViewer's
 * dispatch (ResourceAttachment/rememberResourceAttachmentKind) reads the Asset's actual
 * media_type back from holder-core, not from which markdown syntax referenced it, so this
 * can't be exercised with a fake/unresolvable id the way
 * ComposeUiTest.markdownViewer_resourceImageReference_fallsBackToAnErrorRatherThanCrashing
 * does. Imports directly via HolderNative.importAsset against the built-in "local_directory"
 * provider (no Drive/network involved) rather than going through the photo picker, which is
 * image-only and irrelevant to what's being tested here.
 */
@RunWith(AndroidJUnit4::class)
class ResourceAttachmentViewerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: android.content.Context
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dataDir = context.cacheDir.resolve("holder-resource-viewer-test-${UUID.randomUUID()}")
        check(dataDir.mkdirs()) { "Could not create test data directory: $dataDir" }
        HolderNative.close()
        HolderNative.initialize(
            context = context,
            dataDir = dataDir,
            schemaSql = context.assets.open("schema.sql").bufferedReader().use { it.readText() },
            welcomeContent = "# Welcome\n\nWelcome",
        )
    }

    @After
    fun tearDown() {
        HolderNative.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun markdownViewer_nonImageResource_showsAnOpenExternallyRowNotAnImageDecodeFailure() {
        val project = HolderNative.createProject("Resource viewer test")
        val card = HolderNative.createCard(project.projectId, "Notes", "# Notes\n\nBody")

        val locationId = UUID.randomUUID().toString()
        HolderNative.putLocation(
            locationId = locationId,
            projectId = project.projectId,
            name = "Local",
            provider = "local_directory",
            configuration = emptyMap(),
            now = System.currentTimeMillis() / 1000,
        )

        val sourceFile = File(dataDir, "notes.txt").apply { writeText("plain text content") }
        val result = HolderNative.importAsset(project.projectId, card.cardId, locationId, sourceFile.absolutePath)

        composeRule.setContent {
            HolderMarkdownViewer(
                markdown = "[notes.txt](holder://resource/${result.resourceId})",
                projectId = project.projectId,
                cardId = card.cardId,
                onNavigateToCard = { _, _ -> },
                onNavigateToTag = {},
            )
        }

        composeRule.onNodeWithText("notes.txt").assertIsDisplayed()
    }
}
