package team.holder.android

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.center
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveTo
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.holder.android.ui.screens.CardListScreen
import team.holder.android.ui.sortKeyOrderedSiblings

/** Flowboard Phase 2 (see holder-planning/current/android/flowboard.md): the Board-mode move
 * menu (Part A) and drag-to-reorder handle (Part B) on [CardListScreen], both funneling through
 * [HolderNative.moveCard]. Follows [MilestoneCalendarSmokeTest]'s shape -- seed real data
 * straight through HolderNative into an isolated project/dataDir, render just [CardListScreen]
 * (no navigation/Activity), then drive it with real Compose gestures and assert the resulting
 * order/hierarchy back through HolderNative, not just the rendered list -- the list is a view of
 * that same data, so asserting against the native layer is the stronger check and the one the
 * feature actually promises (menu item -> a real holder_card_move call -> a persisted order). */
@RunWith(AndroidJUnit4::class)
class CardListBoardMoveTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val backgroundWorkTimeoutMs = 15_000L

    private lateinit var context: android.content.Context
    private lateinit var dataDir: File

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dataDir = context.cacheDir.resolve("card-list-board-move-${UUID.randomUUID()}")
        check(dataDir.mkdirs()) { "Could not create test data directory: $dataDir" }
        HolderNative.close()
        HolderNative.initialize(
            context = context,
            dataDir = dataDir,
            schemaSql = context.assets.open("schema.sql").bufferedReader().use { it.readText() },
            welcomeContent = "# Welcome\n\nWelcome",
        )
        // Board mode is a persisted global preference (HolderSettings), not per-project -- force
        // it on for this test and back off afterward so it doesn't leak into other instrumented
        // tests sharing this app process/device.
        runBlocking { HolderSettings.setBoardViewEnabled(context, true) }
    }

    @After
    fun tearDown() {
        runBlocking { HolderSettings.setBoardViewEnabled(context, false) }
        HolderNative.close()
        dataDir.deleteRecursively()
    }

    private fun setContent(projectId: String, projectName: String, onCreateChildCard: (HolderCard) -> Unit = {}) {
        composeRule.setContent {
            CardListScreen(
                projectId = projectId,
                projectName = projectName,
                refreshKey = Unit,
                onCardClick = { _, _ -> },
                onCreateCard = {},
                onCreateChildCard = onCreateChildCard,
                onTrashClick = {},
                onCalendarClick = {},
                onBack = {},
            )
        }
    }

    private fun siblingIds(projectId: String, parentId: String?): List<String> =
        sortKeyOrderedSiblings(parentId, HolderNative.listCards(projectId)).map { it.cardId }

    private fun waitForOrder(projectId: String, parentId: String?, expected: List<String>) {
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            siblingIds(projectId, parentId) == expected
        }
    }

    private fun openMenuAndSelect(cardTitle: String, menuItemText: String) {
        composeRule.onNodeWithText(cardTitle).performTouchInput { longClick() }
        composeRule.onNodeWithText(menuItemText).performClick()
    }

    private fun dragHandleOnto(draggedTitle: String, targetCardId: String, fractionOfTargetHeight: Float) {
        // useUnmergedTree = true: the handle's own contentDescription otherwise merges up into
        // the row's clickable parent (same merge boundary as the folder row's "folder, N items"
        // description), which would resolve this to the *row's* node -- and then down()/moveTo()
        // would inject touches at the row's own center, nowhere near the small trailing-edge
        // handle that actually owns the drag pointerInput, so no drag would ever start.
        val handle = composeRule.onNodeWithContentDescription("Reorder $draggedTitle", useUnmergedTree = true)
        val handleBounds = handle.fetchSemanticsNode().boundsInRoot
        val targetBounds = composeRule.onNodeWithTag("cardRow_$targetCardId").fetchSemanticsNode().boundsInRoot
        val targetY = targetBounds.top + targetBounds.height * fractionOfTargetHeight
        val handleCenter = handleBounds.center
        val localTargetY = handleBounds.height / 2f + (targetY - handleCenter.y)

        handle.performTouchInput {
            down(center)
            // detectDragGestures' touch-slop crossing consumes part of the *first* movement's
            // distance (in whatever direction that movement went) before onDrag starts reporting
            // deltas faithfully -- so a single diagonal move toward the target would silently
            // undershoot the vertical distance by ~touchSlop and land in the wrong drop zone.
            // Spend that one-time slop budget on a pure horizontal move first (row-hit-testing is
            // Y-only, so it doesn't matter), then every vertical step below is reported 1:1.
            moveTo(Offset(center.x + 80f, center.y))
            val steps = 10
            for (i in 1..steps) {
                moveTo(Offset(center.x + 80f, center.y + (localTargetY - center.y) * i / steps))
            }
            up()
        }
    }

    @Test
    fun createChildCard_viaBoardMenu_createsChildUnderParent() {
        val project = HolderNative.createProject("Board move project")
        val parentTitle = "Parent ${UUID.randomUUID()}"
        val parent = HolderNative.createCard(project.projectId, parentTitle, "")

        var createdChild: HolderCard? = null
        setContent(project.projectId, project.name, onCreateChildCard = { card ->
            createdChild = HolderNative.createCard(project.projectId, "Child of ${card.title}", "", card.cardId)
        })

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(parentTitle).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(parentTitle, "Create Child Card")

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) { createdChild != null }
        val child = requireNotNull(createdChild)
        assertEquals(parent.cardId, child.parentCardId)
        assertTrue(HolderNative.listCards(project.projectId).any { it.cardId == child.cardId })
    }

    @Test
    fun moveUpALevel_reparentsCardToGrandparent() {
        val project = HolderNative.createProject("Board move project")
        val parentTitle = "Parent ${UUID.randomUUID()}"
        val parent = HolderNative.createCard(project.projectId, parentTitle, "")
        val childTitle = "Child ${UUID.randomUUID()}"
        val child = HolderNative.createCard(project.projectId, childTitle, "", parent.cardId)

        setContent(project.projectId, project.name)

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(parentTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(parentTitle).performClick() // drill in
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(childTitle).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(childTitle, "Move Up a Level")

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            HolderNative.listCards(project.projectId).find { it.cardId == child.cardId }?.parentCardId == null
        }
        assertNull(HolderNative.listCards(project.projectId).find { it.cardId == child.cardId }?.parentCardId)
    }

    @Test
    fun moveUp_swapsWithPreviousSibling() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(b.title).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(b.title, "Move Up")

        waitForOrder(project.projectId, null, listOf(b.cardId, a.cardId, c.cardId))
    }

    @Test
    fun moveDown_swapsWithNextSibling() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(b.title).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(b.title, "Move Down")

        waitForOrder(project.projectId, null, listOf(a.cardId, c.cardId, b.cardId))
    }

    @Test
    fun moveToTop_movesCardToFrontOfSiblings() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(c.title).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(c.title, "Move to Top")

        waitForOrder(project.projectId, null, listOf(c.cardId, a.cardId, b.cardId))
    }

    @Test
    fun moveToBottom_movesCardToEndOfSiblings() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(a.title).fetchSemanticsNodes().isNotEmpty()
        }

        openMenuAndSelect(a.title, "Move to Bottom")

        waitForOrder(project.projectId, null, listOf(b.cardId, c.cardId, a.cardId))
    }

    @Test
    fun dragBefore_dropsCardBeforeTarget() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(c.title).fetchSemanticsNodes().isNotEmpty()
        }

        // Drag C onto the top ~25% of A's row -> C lands immediately before A.
        dragHandleOnto(draggedTitle = c.title, targetCardId = a.cardId, fractionOfTargetHeight = 0.1f)

        waitForOrder(project.projectId, null, listOf(c.cardId, a.cardId, b.cardId))
    }

    @Test
    fun dragAfter_dropsCardAfterTarget() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        val c = HolderNative.createCard(project.projectId, "C ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId, c.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(a.title).fetchSemanticsNodes().isNotEmpty()
        }

        // Drag A onto the bottom ~25% of B's row -> A lands immediately after B.
        dragHandleOnto(draggedTitle = a.title, targetCardId = b.cardId, fractionOfTargetHeight = 0.9f)

        waitForOrder(project.projectId, null, listOf(b.cardId, a.cardId, c.cardId))
    }

    @Test
    fun dragInto_reparentsCardUnderTarget() {
        val project = HolderNative.createProject("Board move project")
        val a = HolderNative.createCard(project.projectId, "A ${UUID.randomUUID()}", "")
        val b = HolderNative.createCard(project.projectId, "B ${UUID.randomUUID()}", "")
        assertEquals(listOf(a.cardId, b.cardId), siblingIds(project.projectId, null))

        setContent(project.projectId, project.name)
        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(a.title).fetchSemanticsNodes().isNotEmpty()
        }

        // Drag A onto the middle ~50% of B's row -> A reparents as a child of B.
        dragHandleOnto(draggedTitle = a.title, targetCardId = b.cardId, fractionOfTargetHeight = 0.5f)

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            HolderNative.listCards(project.projectId).find { it.cardId == a.cardId }?.parentCardId == b.cardId
        }
        assertEquals(b.cardId, HolderNative.listCards(project.projectId).find { it.cardId == a.cardId }?.parentCardId)
    }
}
