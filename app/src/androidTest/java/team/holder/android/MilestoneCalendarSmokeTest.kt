package team.holder.android

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import team.holder.android.ui.screens.CalendarScreen

/** The seam most likely to regress silently: a milestone's range-query result (JNI/libholder)
 * rendered by CalendarScreen's Compose list, cardTitle and all.
 * [HolderNativeIntegrationTest.milestones_roundTripThroughTheFullJniBoundary] already covers the
 * JNI round trip itself (listMilestonesInRange returning the right cardTitle); this test's job
 * is just the last hop, that CalendarScreen actually shows it.
 *
 * Deliberately not a full-Activity/navigation test (that's how this used to work, driving
 * through Home -> New card -> Tools -> Milestones -> Add milestone by hand): every one of those
 * navigation waits was a fresh chance to time out on a loaded CI emulator, for coverage this
 * test doesn't need -- it isn't checking that navigation wiring, only this one screen's render
 * of already-created data. Seeding through HolderNative directly into its own isolated project
 * (own dataDir, not the shared "Home" project WholeAppSmokeTest uses) also drops the
 * unique-title-plus-teardown-delete dance that sharing requires. */
@RunWith(AndroidJUnit4::class)
class MilestoneCalendarSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    // This screen's only async work is one HolderNative.listMilestonesInRange round trip inside
    // a LaunchedEffect -- no navigation animation or multi-screen composition to wait on -- so it
    // doesn't need the old test's 60s budget. Matches ComposeUiTest's own background-work timeout
    // for the same class of wait: a real native call landing under CI load, not just composition.
    private val backgroundWorkTimeoutMs = 15_000L

    private lateinit var dataDir: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dataDir = context.cacheDir.resolve("milestone-calendar-smoke-${UUID.randomUUID()}")
        check(dataDir.mkdirs()) { "Could not create test data directory: $dataDir" }
        // Force a fresh context on this test's own dataDir -- initialize() is a no-op reopen
        // once contextHandle is already set, so a handle left open by a previous test would
        // otherwise seed/query the previous test's dataDir instead of this one's.
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
    fun addMilestone_showsUpOnTheCalendarWithTheCardTitle() {
        val title = "Milestone smoke card ${UUID.randomUUID()}"
        val project = HolderNative.createProject("Milestone smoke project")
        val card = HolderNative.createCard(project.projectId, title, "Created by milestone smoke test.")
        HolderNative.addCardMilestone(
            cardId = card.cardId,
            startAt = System.currentTimeMillis() / 1000,
            allDay = true,
        )

        var navigatedCardId: String? = null
        var navigatedTitle: String? = null
        composeRule.setContent {
            CalendarScreen(
                projectId = project.projectId,
                refreshKey = Unit,
                onNavigateToCard = { cardId, cardTitle ->
                    navigatedCardId = cardId
                    navigatedTitle = cardTitle
                },
                onBack = {},
            )
        }

        composeRule.waitUntil(timeoutMillis = backgroundWorkTimeoutMs) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()

        composeRule.onNodeWithText(title).performClick()
        assertEquals(card.cardId, navigatedCardId)
        assertEquals(title, navigatedTitle)
    }
}
