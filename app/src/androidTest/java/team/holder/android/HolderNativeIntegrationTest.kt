package team.holder.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Representative Kotlin -> JNI -> C API -> libholder tests.
 *
 * These deliberately cover a small number of end-to-end round trips rather than duplicating the
 * exhaustive holder-core C API suite. Every test uses a unique cache directory and closes the
 * process-wide HolderNative context after each case.
 */
@RunWith(AndroidJUnit4::class)
class HolderNativeIntegrationTest {
    private lateinit var context: android.content.Context
    private lateinit var dataDir: File
    private lateinit var schemaSql: String

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        dataDir = context.cacheDir.resolve("holder-jni-test-${UUID.randomUUID()}")
        check(dataDir.mkdirs()) { "Could not create test data directory: $dataDir" }
        schemaSql = context.assets.open("schema.sql").bufferedReader().use { it.readText() }
        HolderNative.close()
    }

    @After
    fun tearDown() {
        HolderNative.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun nativeVersion_isAvailableThroughJni() {
        val version = HolderNative.version()

        assertFalse(version.startsWith("native load failed:"))
        assertFalse(version.startsWith("native call failed:"))
        assertTrue(version.isNotBlank())
    }

    @Test
    fun initialize_bootstrapsHomeProjectAndWelcomeCard() {
        val welcome = "# Welcome 🌍\n\nHolder is ready."

        initialize(welcome)

        val projects = HolderNative.listProjects()
        assertEquals(1, projects.size)
        assertEquals("Home", projects.single().name)

        val cards = HolderNative.listCards(projects.single().projectId)
        assertEquals(1, cards.size)
        assertEquals("Welcome 🌍", cards.single().title)
        assertEquals(welcome, HolderNative.getCardContent(cards.single().cardId))
    }

    @Test
    fun projectAndCardRoundTrip_preservesUtf8AndSearchResults() {
        initialize("# Welcome\n\nWelcome")

        val project = HolderNative.createProject("Проект 🚀")
        val title = "Café 日本語 📝"
        val content = "# $title\n\nПривет, мир — résumé."
        val card = HolderNative.createCard(project.projectId, title, content)

        assertEquals(project.projectId, card.projectId)
        assertEquals(title, card.title)
        assertEquals(content, HolderNative.getCardContent(card.cardId))
        assertEquals(card, HolderNative.listCards(project.projectId).single { it.cardId == card.cardId })

        val results = HolderNative.searchCards(project.projectId, "résumé")
        assertEquals(1, results.size)
        assertEquals(card.cardId, results.single().cardId)
        assertEquals(title, results.single().title)
        assertNotNull(results.single().snippet)
    }

    @Test
    fun closeAndReopen_preservesProjectAndCardData() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Persistent project")
        val card = HolderNative.createCard(project.projectId, "Persistent card", "# Persistent card\n\nBody")

        HolderNative.close()
        initialize("# Welcome\n\nWelcome")

        assertEquals(project, HolderNative.listProjects().single { it.projectId == project.projectId })
        assertEquals("# Persistent card\n\nBody", HolderNative.getCardContent(card.cardId))
    }

    @Test
    fun deletingMissingCard_returnsNativeErrorThroughKotlinBoundary() {
        initialize("# Welcome\n\nWelcome")

        val error = assertThrows(RuntimeException::class.java) {
            HolderNative.deleteCard("card-that-does-not-exist")
        }

        assertTrue(error.message.orEmpty().isNotBlank())
    }

    @Test
    fun trashRestorePurge_roundTripsThroughTheFullJniBoundary() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Trash test project")
        val card = HolderNative.createCard(project.projectId, "Doomed", "original content")

        HolderNative.deleteCard(card.cardId)

        assertTrue(HolderNative.listCards(project.projectId).none { it.cardId == card.cardId })
        val trashed = HolderNative.listTrashedCards(project.projectId).single { it.cardId == card.cardId }
        assertNotNull(trashed.deletedAt)

        val restored = HolderNative.restoreCard(card.cardId)
        assertEquals(card.cardId, restored.cardId)
        assertEquals("original content", HolderNative.getCardContent(card.cardId))
        assertEquals(1, HolderNative.listCards(project.projectId).count { it.cardId == card.cardId })
        assertTrue(HolderNative.listTrashedCards(project.projectId).none { it.cardId == card.cardId })

        HolderNative.deleteCard(card.cardId)
        HolderNative.purgeCard(card.cardId)

        assertTrue(HolderNative.listTrashedCards(project.projectId).none { it.cardId == card.cardId })
        assertThrows(RuntimeException::class.java) { HolderNative.getCardContent(card.cardId) }
    }

    @Test
    fun cardLinks_roundTripThroughTheFullJniBoundary() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Connections test project")
        val blocked = HolderNative.createCard(project.projectId, "Blocked task", "content")
        val blocking = HolderNative.createCard(project.projectId, "Blocking task", "content")

        val empty = HolderNative.listCardLinks(blocked.cardId)
        assertTrue(empty.outgoing.isEmpty())
        assertTrue(empty.backlinks.isEmpty())

        val outgoing = HolderNative.addCardLink(blocked.cardId, blocking.cardId, "blocked_by", "waiting")
        assertEquals(1, outgoing.size)
        assertEquals(blocking.cardId, outgoing.single().toCardId)
        assertEquals("Blocking task", outgoing.single().toTitle)

        val blockingLinks = HolderNative.listCardLinks(blocking.cardId)
        assertEquals(1, blockingLinks.backlinks.size)
        assertEquals(blocked.cardId, blockingLinks.backlinks.single().fromCardId)
        assertEquals("Blocked task", blockingLinks.backlinks.single().fromTitle)

        HolderNative.removeCardLink(blocked.cardId, blocking.cardId, "blocked_by")
        assertTrue(HolderNative.listCardLinks(blocked.cardId).outgoing.isEmpty())
        assertTrue(HolderNative.listCardLinks(blocking.cardId).backlinks.isEmpty())
    }

    @Test
    fun cardLinks_reflectParentChildHierarchyThroughTheFullJniBoundary() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Hierarchy test project")
        val parent = HolderNative.createCard(project.projectId, "Parent card", "content")
        val child = HolderNative.createCard(project.projectId, "Child card", "content", parent.cardId)

        val parentLinks = HolderNative.listCardLinks(parent.cardId)
        assertEquals(null, parentLinks.parent)
        assertEquals(1, parentLinks.children.size)
        assertEquals(child.cardId, parentLinks.children.single().cardId)

        val childLinks = HolderNative.listCardLinks(child.cardId)
        assertEquals(parent.cardId, childLinks.parent?.cardId)
        assertEquals("Parent card", childLinks.parent?.title)
        assertTrue(childLinks.children.isEmpty())
    }

    @Test
    fun tags_roundTripThroughTheFullJniBoundary() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Tags test project")
        val card = HolderNative.createCard(project.projectId, "Tagged", "Body mentioning #TODO and #android.")

        assertEquals(listOf("android", "todo"), HolderNative.listCardTags(card.cardId))

        val withTodo = HolderNative.cardsWithTag(project.projectId, "TODO")
        assertEquals(1, withTodo.size)
        assertEquals(card.cardId, withTodo.single().cardId)

        val projectTags = HolderNative.listProjectTags(project.projectId)
        assertEquals(2, projectTags.size)
        assertTrue(projectTags.all { it.count == 1 })

        HolderNative.updateCard(card.cardId, "Tagged", "Only #urgent now.")
        assertEquals(listOf("urgent"), HolderNative.listCardTags(card.cardId))
        assertTrue(HolderNative.cardsWithTag(project.projectId, "todo").isEmpty())
    }

    @Test
    fun milestones_roundTripThroughTheFullJniBoundary() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Milestones test project")
        val card = HolderNative.createCard(project.projectId, "Renewal", "content")

        assertTrue(HolderNative.listCardMilestones(card.cardId).isEmpty())

        val start = 1_800_000_000L
        val afterAdd = HolderNative.addCardMilestone(
            cardId = card.cardId,
            startAt = start,
            endAt = null,
            allDay = true,
            kind = "Renewal",
            description = "Car insurance",
        )
        assertEquals(1, afterAdd.size)
        val added = afterAdd.single()
        assertEquals(card.cardId, added.cardId)
        assertEquals(start, added.startAt)
        assertNull(added.endAt)
        assertTrue(added.allDay)
        assertEquals("Renewal", added.kind)
        assertEquals("Car insurance", added.description)

        assertEquals(added, HolderNative.listCardMilestones(card.cardId).single())

        val inRange = HolderNative.listMilestonesInRange(project.projectId, start - 3600, start + 3600)
        assertEquals(1, inRange.size)
        assertEquals(added.milestoneId, inRange.single().milestoneId)
        assertEquals("Renewal", inRange.single().cardTitle)

        assertTrue(HolderNative.listMilestonesInRange(project.projectId, start + 7200, start + 10_800).isEmpty())

        HolderNative.removeCardMilestone(card.cardId, added.milestoneId)
        assertTrue(HolderNative.listCardMilestones(card.cardId).isEmpty())
        assertTrue(HolderNative.listMilestonesInRange(project.projectId, start - 3600, start + 3600).isEmpty())
    }

    @Test
    fun milestones_supportASpanWithAnEndAndSurviveCardTrash() {
        initialize("# Welcome\n\nWelcome")
        val project = HolderNative.createProject("Milestone span test project")
        val card = HolderNative.createCard(project.projectId, "Conference", "content")

        val start = 1_800_000_000L
        val end = start + 2 * 24 * 60 * 60
        val added = HolderNative.addCardMilestone(cardId = card.cardId, startAt = start, endAt = end)
            .single()
        assertEquals(end, added.endAt)

        val inRange = HolderNative.listMilestonesInRange(project.projectId, start - 3600, start + 3600)
        assertEquals(1, inRange.size)

        HolderNative.deleteCard(card.cardId)
        assertTrue(HolderNative.listMilestonesInRange(project.projectId, start - 3600, end + 3600).isEmpty())
    }

    private fun initialize(welcomeContent: String) {
        HolderNative.initialize(
            context = context,
            dataDir = dataDir,
            schemaSql = schemaSql,
            welcomeContent = welcomeContent,
        )
    }
}
