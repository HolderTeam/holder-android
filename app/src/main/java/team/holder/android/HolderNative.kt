package team.holder.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class HolderProject(
    val projectId: String,
    val name: String,
    val gitRemoteUrl: String?,
    val privacyMode: String,
)

data class HolderCard(
    val cardId: String,
    val projectId: String,
    val title: String,
    val parentCardId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Manual sibling order set by Flowboard's drag-to-reorder on desktop; defaults to
     * creation order (each new card gets a higher sort_key than its existing siblings)
     * until someone explicitly rearranges them. Scoped to cards sharing the same
     * parentCardId -- not comparable across siblings of different parents. */
    val sortKey: Double,
    val deletedAt: Long? = null,
)

data class HolderOutgoingLink(
    val toCardId: String,
    val toType: String,
    val kind: String,
    val label: String?,
    val toTitle: String?,
)

data class HolderBacklink(
    val fromCardId: String,
    val kind: String,
    val label: String?,
    val fromTitle: String?,
)

data class HolderCardRef(
    val cardId: String,
    val title: String,
)

data class HolderCardLinks(
    val outgoing: List<HolderOutgoingLink>,
    val backlinks: List<HolderBacklink>,
    /** Hierarchy (parent_card_id), shown automatically -- not editable through
     * addCardLink/removeCardLink. Null at the root of a project. */
    val parent: HolderCardRef?,
    val children: List<HolderCardRef>,
)

data class HolderTagCount(
    val tag: String,
    val count: Int,
)

data class HolderLinkKind(
    val id: String,
    val forward: String,
    val reverse: String,
)

data class HolderSearchResult(
    val cardId: String,
    val title: String,
    val snippet: String,
)

data class HolderMilestone(
    val milestoneId: String,
    val cardId: String,
    val startAt: Long,
    val endAt: Long?,
    val allDay: Boolean,
    val kind: String?,
    val description: String?,
    val createdAt: Long,
    val updatedAt: Long,
    /** Only populated by listMilestonesInRange (the Calendar query) -- null from
     * listCardMilestones, where the caller already knows which card this is. */
    val cardTitle: String? = null,
)

data class GitTestRemoteResult(
    val status: String,
    val remoteHasHead: Boolean,
    val errorMessage: String?,
)

data class GitPushResult(
    val status: String,
    val aheadCount: Int,
    val behindCount: Int,
    val errorMessage: String?,
)

data class GitPullResult(
    val status: String,
    val errorMessage: String?,
    val conflictsResolved: Int,
)

data class GitSyncStatus(
    val lastPushAt: Long?,
    val lastPullAt: Long?,
    val uncommittedChangesCount: Int,
    val unpushedCommitsCount: Int,
    val lastPushStatus: String?,
    val lastPullStatus: String?,
    val lastSyncError: String?,
)

data class GitSyncIfDueResult(
    val pullAttempted: Boolean,
    val pullStatus: String?,
    val pushAttempted: Boolean,
    val pushStatus: String?,
)

data class EncryptionCheckResult(
    val privacyMode: String,
    val ok: Boolean,
    val checkedFiles: Int,
    val unsafeFiles: Int,
    val unsafePaths: List<String>,
    val message: String,
)

data class RecoveryTokenExportResult(
    val projectId: String,
    val keyId: String,
    val recoveryToken: String,
)

data class RecoveryTokenMetadata(
    val projectId: String,
    val projectKeyId: String,
    val projectName: String?,
    val gitRemoteUrl: String?,
)

data class RecoveryTokenImportGlobalResult(
    val projectId: String,
    val projectCreated: Boolean,
    val remoteHintPresent: Boolean,
    val remoteConfigured: Boolean,
    val remoteError: String?,
    val pullStatus: String,
    val pullError: String?,
)

/**
 * Kotlin -> JNI -> C ABI -> libholder boundary. Opens a single native
 * holder_context on first use (see initialize) and keeps it open for the
 * app's lifetime instead of reopening the SQLite store on every call.
 */
object HolderNative {
    private const val DEFAULT_PROJECT_NAME = "Home"
    private const val WELCOME_CARD_TITLE_FALLBACK = "Welcome"

    private val loadError: Throwable? = runCatching {
        System.loadLibrary("holder_jni")
    }.exceptionOrNull()

    @Volatile
    private var contextHandle: Long = 0L

    private external fun nativeVersion(): String
    private external fun nativeContextOpen(dataDir: String, schemaSql: String): Long
    private external fun nativeContextClose(contextHandle: Long)
    private external fun nativeDatabaseRebuild(dataDir: String, schemaSql: String, dryRun: Boolean): String
    private external fun nativeProjectList(contextHandle: Long): String
    private external fun nativeCardList(contextHandle: Long, projectId: String): String
    private external fun nativeCardGetContent(contextHandle: Long, cardId: String): String
    private external fun nativeProjectCreate(
        contextHandle: Long,
        name: String,
        rootPath: String?,
        privacyMode: String?,
    ): String
    private external fun nativeCardCreate(
        contextHandle: Long,
        projectId: String,
        title: String,
        content: String?,
        parentCardId: String?,
    ): String
    private external fun nativeEnsureDefaultProject(
        contextHandle: Long,
        name: String,
        welcomeTitle: String,
        welcomeContent: String?,
    ): String
    private external fun nativeProjectRename(contextHandle: Long, projectId: String, name: String): String
    private external fun nativeProjectDelete(contextHandle: Long, projectId: String)
    private external fun nativeCardUpdateContent(
        contextHandle: Long,
        cardId: String,
        content: String,
        title: String?,
    ): String
    private external fun nativeCardDelete(contextHandle: Long, cardId: String)
    private external fun nativeCardListTrashed(contextHandle: Long, projectId: String): String
    private external fun nativeCardRestore(contextHandle: Long, cardId: String): String
    private external fun nativeCardPurge(contextHandle: Long, cardId: String)
    private external fun nativeCardListLinks(contextHandle: Long, cardId: String): String
    private external fun nativeCardLinkAdd(
        contextHandle: Long,
        fromCardId: String,
        toCardId: String,
        kind: String,
        label: String?,
    ): String
    private external fun nativeCardLinkRemove(
        contextHandle: Long,
        fromCardId: String,
        toCardId: String,
        kind: String,
    )
    private external fun nativeListLinkKinds(): String
    private external fun nativeCardListTags(contextHandle: Long, cardId: String): String
    private external fun nativeCardsWithTag(contextHandle: Long, projectId: String, tag: String): String
    private external fun nativeProjectListTags(contextHandle: Long, projectId: String): String
    private external fun nativeCardListMilestones(contextHandle: Long, cardId: String): String
    private external fun nativeCardMilestoneAdd(
        contextHandle: Long,
        cardId: String,
        startAt: Long,
        hasEndAt: Boolean,
        endAt: Long,
        allDay: Boolean,
        kind: String?,
        description: String?,
    ): String
    private external fun nativeCardMilestoneRemove(contextHandle: Long, cardId: String, milestoneId: String)
    private external fun nativeProjectListMilestonesInRange(
        contextHandle: Long,
        projectId: String,
        from: Long,
        to: Long,
    ): String
    private external fun nativeCardSearch(
        contextHandle: Long,
        projectId: String,
        query: String,
        limit: Int,
        offset: Int,
    ): String
    private external fun nativeProjectUpdateGitRemote(
        contextHandle: Long,
        projectId: String,
        remoteUrl: String?,
    ): String
    private external fun nativeGitTestRemote(contextHandle: Long, projectId: String, branch: String?): String
    private external fun nativeGitPush(
        contextHandle: Long,
        projectId: String,
        branch: String?,
        setUpstream: Boolean,
    ): String
    private external fun nativeGitPull(contextHandle: Long, projectId: String): String
    private external fun nativeGitSyncStatus(contextHandle: Long, projectId: String): String
    private external fun nativeGitSyncIfDue(
        contextHandle: Long,
        projectId: String,
        pushIntervalSeconds: Int,
        pullIntervalSeconds: Int,
    ): String
    private external fun nativeEncryptionCheck(contextHandle: Long, projectId: String): String
    private external fun nativeRecoveryTokenExport(contextHandle: Long, projectId: String, pin: String): String
    private external fun nativeRecoveryTokenImport(
        contextHandle: Long,
        projectId: String,
        pin: String,
        recoveryToken: String,
    ): String
    private external fun nativeRecoveryTokenInspect(pin: String, recoveryToken: String): String
    private external fun nativeRecoveryTokenImportGlobal(
        contextHandle: Long,
        pin: String,
        recoveryToken: String,
    ): String

    fun version(): String {
        loadError?.let {
            return "native load failed: ${it.message ?: it::class.java.simpleName}"
        }

        return runCatching {
            nativeVersion()
        }.getOrElse {
            "native call failed: ${it.message ?: it::class.java.simpleName}"
        }
    }

    /**
     * Opens the native store (once; safe to call again) and, on first launch,
     * bootstraps a default Home project with a welcome card.
     */
    @Synchronized
    fun initialize(context: Context, dataDir: File, schemaSql: String, welcomeContent: String) {
        loadError?.let { throw it }

        if (contextHandle == 0L) {
            // Recovery of encrypted projects needs the platform keyring before
            // holder_context_open can reconstruct the SQLite projection.
            check(team.holder.android.keyring.AndroidKeyringStore.registerWithNative(context) == 0) {
                "Could not register the Android platform keyring"
            }
            contextHandle = nativeContextOpen(dataDir.absolutePath, schemaSql)
            // Best-effort: git sync still falls back to the (nonexistent, on Android)
            // default ssh-agent/~/.ssh lookup if this fails, so a Keystore hiccup here
            // shouldn't block the rest of the app from opening.
            runCatching { team.holder.android.git.GitIdentity.registerWithNative(contextHandle, dataDir) }
        }
        nativeEnsureDefaultProject(
            contextHandle,
            DEFAULT_PROJECT_NAME,
            deriveWelcomeTitle(welcomeContent, WELCOME_CARD_TITLE_FALLBACK),
            welcomeContent,
        )
    }

    /**
     * Reconstructs Holder's disposable SQLite projection from the Git-backed
     * project files. The native context is closed for the swap and reopened
     * before this method returns, including after a dry run.
     */
    @Synchronized
    fun rebuildDatabase(
        context: Context,
        dataDir: File,
        schemaSql: String,
        welcomeContent: String,
        dryRun: Boolean = false,
    ): JSONObject {
        loadError?.let { throw it }
        close()
        check(team.holder.android.keyring.AndroidKeyringStore.registerWithNative(context) == 0) {
            "Could not register the Android platform keyring"
        }
        return try {
            JSONObject(nativeDatabaseRebuild(dataDir.absolutePath, schemaSql, dryRun))
        } finally {
            initialize(context, dataDir, schemaSql, welcomeContent)
        }
    }

    /** Closes the native store. Safe to call even if it was never opened. */
    @Synchronized
    fun close() {
        if (contextHandle != 0L) {
            nativeContextClose(contextHandle)
            contextHandle = 0L
        }
    }

    fun listProjects(): List<HolderProject> {
        val projects = JSONArray(nativeProjectList(requireContext()))
        return List(projects.length()) { index -> parseProject(projects.getJSONObject(index)) }
    }

    fun listCards(projectId: String): List<HolderCard> {
        val cards = JSONArray(nativeCardList(requireContext(), projectId))
        return List(cards.length()) { index -> parseCard(cards.getJSONObject(index)) }
    }

    fun getCardContent(cardId: String): String {
        return nativeCardGetContent(requireContext(), cardId)
    }

    /** privacyMode null defaults (server-side) to "plain". "encrypted_git" is also valid. */
    fun createProject(name: String, privacyMode: String? = null): HolderProject =
        parseProject(JSONObject(nativeProjectCreate(requireContext(), name, null, privacyMode)))

    fun renameProject(projectId: String, name: String): HolderProject =
        parseProject(JSONObject(nativeProjectRename(requireContext(), projectId, name)))

    fun deleteProject(projectId: String) {
        nativeProjectDelete(requireContext(), projectId)
    }

    fun createCard(projectId: String, title: String, content: String, parentCardId: String? = null): HolderCard =
        parseCard(JSONObject(nativeCardCreate(requireContext(), projectId, title, content, parentCardId)))

    fun updateCard(cardId: String, title: String, content: String): HolderCard =
        parseCard(JSONObject(nativeCardUpdateContent(requireContext(), cardId, content, title)))

    /** Soft-deletes (trashes) a card; it stops appearing in listCards until restored or purged. */
    fun deleteCard(cardId: String) {
        nativeCardDelete(requireContext(), cardId)
    }

    fun listTrashedCards(projectId: String): List<HolderCard> {
        val cards = JSONArray(nativeCardListTrashed(requireContext(), projectId))
        return List(cards.length()) { index -> parseCard(cards.getJSONObject(index)) }
    }

    /** Restores a trashed card so it reappears in listCards. Fails if cardId isn't trashed. */
    fun restoreCard(cardId: String): HolderCard =
        parseCard(JSONObject(nativeCardRestore(requireContext(), cardId)))

    /** Permanently deletes a trashed card. Irreversible. Fails if cardId isn't trashed. */
    fun purgeCard(cardId: String) {
        nativeCardPurge(requireContext(), cardId)
    }

    /** Explicit connections only -- not hierarchy (parent/child) or inline [[wikilinks]]. */
    fun listCardLinks(cardId: String): HolderCardLinks {
        val json = JSONObject(nativeCardListLinks(requireContext(), cardId))
        val outgoing = json.getJSONArray("outgoing")
        val backlinks = json.getJSONArray("backlinks")
        val children = json.getJSONArray("children")
        return HolderCardLinks(
            outgoing = List(outgoing.length()) { index -> parseOutgoingLink(outgoing.getJSONObject(index)) },
            backlinks = List(backlinks.length()) { index -> parseBacklink(backlinks.getJSONObject(index)) },
            parent = json.optJSONObject("parent")?.let { parseCardRef(it) },
            children = List(children.length()) { index -> parseCardRef(children.getJSONObject(index)) },
        )
    }

    /** Adds (or updates, if fromCardId/toCardId/kind already matches) an explicit outgoing
     * connection. Returns fromCardId's updated outgoing connection list. */
    fun addCardLink(fromCardId: String, toCardId: String, kind: String, label: String? = null): List<HolderOutgoingLink> {
        val outgoing = JSONArray(nativeCardLinkAdd(requireContext(), fromCardId, toCardId, kind, label))
        return List(outgoing.length()) { index -> parseOutgoingLink(outgoing.getJSONObject(index)) }
    }

    fun removeCardLink(fromCardId: String, toCardId: String, kind: String) {
        nativeCardLinkRemove(requireContext(), fromCardId, toCardId, kind)
    }

    @Volatile
    private var linkKindCatalog: List<HolderLinkKind>? = null

    /** Holder's built-in vocabulary of card_links.kind values with curated English labels.
     * Fetched once and cached -- static reference data, independent of which project/context
     * is open. */
    fun listLinkKinds(): List<HolderLinkKind> {
        linkKindCatalog?.let { return it }
        val kinds = JSONArray(nativeListLinkKinds())
        val parsed = List(kinds.length()) { index ->
            val entry = kinds.getJSONObject(index)
            HolderLinkKind(
                id = entry.getString("id"),
                forward = entry.getString("forward"),
                reverse = entry.getString("reverse"),
            )
        }
        linkKindCatalog = parsed
        return parsed
    }

    /** Resolves kind to a display label: the catalog's forward/reverse label if it's one of
     * Holder's built-in link kinds, otherwise a humanized (snake_case -> "Snake case") fallback
     * shown the same regardless of direction, since an arbitrary custom kind has no known
     * reverse. */
    fun linkKindLabel(kind: String, forward: Boolean): String {
        val entry = listLinkKinds().firstOrNull { it.id == kind }
        if (entry != null) return if (forward) entry.forward else entry.reverse
        return kind.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /** cardId's #tags, as extracted from its body -- not editable directly, edit the #tag text
     * in the body instead. */
    fun listCardTags(cardId: String): List<String> {
        val tags = JSONArray(nativeCardListTags(requireContext(), cardId))
        return List(tags.length()) { index -> tags.getString(index) }
    }

    /** Cards in projectId carrying tag (case-insensitive). */
    fun cardsWithTag(projectId: String, tag: String): List<HolderCardRef> {
        val cards = JSONArray(nativeCardsWithTag(requireContext(), projectId, tag))
        return List(cards.length()) { index -> parseCardRef(cards.getJSONObject(index)) }
    }

    /** Every distinct tag in projectId with how many cards carry it, most-used first. */
    fun listProjectTags(projectId: String): List<HolderTagCount> {
        val tags = JSONArray(nativeProjectListTags(requireContext(), projectId))
        return List(tags.length()) { index ->
            val entry = tags.getJSONObject(index)
            HolderTagCount(tag = entry.getString("tag"), count = entry.getInt("count"))
        }
    }

    /** cardId's milestones, ordered by start (soonest first). */
    fun listCardMilestones(cardId: String): List<HolderMilestone> {
        val milestones = JSONArray(nativeCardListMilestones(requireContext(), cardId))
        return List(milestones.length()) { index -> parseMilestone(milestones.getJSONObject(index)) }
    }

    /** Adds a milestone to cardId. endAt null means a point in time rather than a span (as
     * opposed to end_at genuinely being 0, which native distinguishes via a separate has_end_at
     * flag, not by treating 0 as absent). Returns cardId's updated milestone list. */
    fun addCardMilestone(
        cardId: String,
        startAt: Long,
        endAt: Long? = null,
        allDay: Boolean = false,
        kind: String? = null,
        description: String? = null,
    ): List<HolderMilestone> {
        val milestones = JSONArray(
            nativeCardMilestoneAdd(
                requireContext(),
                cardId,
                startAt,
                endAt != null,
                endAt ?: 0L,
                allDay,
                kind,
                description,
            )
        )
        return List(milestones.length()) { index -> parseMilestone(milestones.getJSONObject(index)) }
    }

    /** A no-op, not an error, if milestoneId doesn't exist or belongs to a different card. */
    fun removeCardMilestone(cardId: String, milestoneId: String) {
        nativeCardMilestoneRemove(requireContext(), cardId, milestoneId)
    }

    /** Every milestone in projectId whose start falls within [from, to] (inclusive), ordered by
     * start -- the Calendar's primary query. Never includes a trashed card's milestones. */
    fun listMilestonesInRange(projectId: String, from: Long, to: Long): List<HolderMilestone> {
        val milestones =
            JSONArray(nativeProjectListMilestonesInRange(requireContext(), projectId, from, to))
        return List(milestones.length()) { index -> parseMilestone(milestones.getJSONObject(index)) }
    }

    fun searchCards(projectId: String, query: String, limit: Int = 50): List<HolderSearchResult> {
        val results = JSONArray(nativeCardSearch(requireContext(), projectId, query, limit, 0))
        return List(results.length()) { index ->
            val result = results.getJSONObject(index)
            HolderSearchResult(
                cardId = result.getString("card_id"),
                title = result.getString("title"),
                snippet = result.getString("snippet"),
            )
        }
    }

    /** remoteUrl null clears the configured remote. */
    fun updateProjectGitRemote(projectId: String, remoteUrl: String?): HolderProject =
        parseProject(JSONObject(nativeProjectUpdateGitRemote(requireContext(), projectId, remoteUrl)))

    fun testGitRemote(projectId: String, branch: String? = null): GitTestRemoteResult {
        val json = JSONObject(nativeGitTestRemote(requireContext(), projectId, branch))
        return GitTestRemoteResult(
            status = json.getString("status"),
            remoteHasHead = json.optBoolean("remote_has_head", false),
            errorMessage = json.optStringOrNull("error_message"),
        )
    }

    fun pushGit(projectId: String, branch: String? = null, setUpstream: Boolean = true): GitPushResult {
        val json = JSONObject(nativeGitPush(requireContext(), projectId, branch, setUpstream))
        return GitPushResult(
            status = json.getString("status"),
            aheadCount = json.optInt("ahead_count", 0),
            behindCount = json.optInt("behind_count", 0),
            errorMessage = json.optStringOrNull("error_message"),
        )
    }

    fun pullGit(projectId: String): GitPullResult {
        val json = JSONObject(nativeGitPull(requireContext(), projectId))
        return GitPullResult(
            status = json.getString("status"),
            errorMessage = json.optStringOrNull("error_message"),
            conflictsResolved = json.optInt("conflicts_resolved", 0),
        )
    }

    fun gitSyncStatus(projectId: String): GitSyncStatus {
        val sync = JSONObject(nativeGitSyncStatus(requireContext(), projectId)).getJSONObject("sync")
        return GitSyncStatus(
            lastPushAt = sync.optLongOrNull("last_push_at"),
            lastPullAt = sync.optLongOrNull("last_pull_at"),
            uncommittedChangesCount = sync.optInt("uncommitted_changes_count", 0),
            unpushedCommitsCount = sync.optInt("unpushed_commits_count", 0),
            lastPushStatus = sync.optStringOrNull("last_push_status"),
            lastPullStatus = sync.optStringOrNull("last_pull_status"),
            lastSyncError = sync.optStringOrNull("last_sync_error"),
        )
    }

    /**
     * Pulls and/or pushes only if enough time has passed since the last attempt of each,
     * per the same cadence policy holder-daemon's background worker uses -- intended for a
     * periodic background job (see GitSyncWorker) to call on a tighter schedule than the
     * desired sync interval, without over-syncing. A no-op if the project has no remote
     * configured, or if neither is due yet. intervalSeconds <= 0 uses the built-in default
     * (1200s push / 300s pull).
     */
    fun gitSyncIfDue(
        projectId: String,
        pushIntervalSeconds: Int = 0,
        pullIntervalSeconds: Int = 0,
    ): GitSyncIfDueResult {
        val json = JSONObject(
            nativeGitSyncIfDue(requireContext(), projectId, pushIntervalSeconds, pullIntervalSeconds),
        )
        return GitSyncIfDueResult(
            pullAttempted = json.getBoolean("pull_attempted"),
            pullStatus = json.optStringOrNull("pull_status"),
            pushAttempted = json.getBoolean("push_attempted"),
            pushStatus = json.optStringOrNull("push_status"),
        )
    }

    fun encryptionCheck(projectId: String): EncryptionCheckResult {
        val json = JSONObject(nativeEncryptionCheck(requireContext(), projectId))
        val check = json.getJSONObject("check")
        val unsafePathsJson = check.getJSONArray("unsafe_paths")
        return EncryptionCheckResult(
            privacyMode = json.getString("privacy_mode"),
            ok = check.getBoolean("ok"),
            checkedFiles = check.getInt("checked_files"),
            unsafeFiles = check.getInt("unsafe_files"),
            unsafePaths = List(unsafePathsJson.length()) { unsafePathsJson.getString(it) },
            message = check.getString("message"),
        )
    }

    fun exportRecoveryToken(projectId: String, pin: String): RecoveryTokenExportResult {
        val json = JSONObject(nativeRecoveryTokenExport(requireContext(), projectId, pin))
        return RecoveryTokenExportResult(
            projectId = json.getString("project_id"),
            keyId = json.getString("key_id"),
            recoveryToken = json.getString("recovery_token"),
        )
    }

    /** Re-establishes projectId's key material from a recovery token; projectId must already
     * exist and match the token's own project id. See importRecoveryTokenGlobal for recovering
     * onto a device that doesn't have the project yet. */
    fun importRecoveryToken(projectId: String, pin: String, recoveryToken: String) {
        nativeRecoveryTokenImport(requireContext(), projectId, pin, recoveryToken)
    }

    /** Decrypts a recovery token's metadata without importing anything, so a caller can show
     * the user what they're about to recover before committing. */
    fun inspectRecoveryToken(pin: String, recoveryToken: String): RecoveryTokenMetadata {
        val json = JSONObject(nativeRecoveryTokenInspect(pin, recoveryToken))
        return RecoveryTokenMetadata(
            projectId = json.getString("project_id"),
            projectKeyId = json.getString("project_key_id"),
            projectName = json.optStringOrNull("project_name"),
            gitRemoteUrl = json.optStringOrNull("git_remote_url"),
        )
    }

    /** The device-setup path: recovers a project from just a PIN and a recovery token, creating
     * it first if this device has never seen it before, and pulling its remote if the token
     * includes one. */
    fun importRecoveryTokenGlobal(pin: String, recoveryToken: String): RecoveryTokenImportGlobalResult {
        val json = JSONObject(nativeRecoveryTokenImportGlobal(requireContext(), pin, recoveryToken))
        return RecoveryTokenImportGlobalResult(
            projectId = json.getString("project_id"),
            projectCreated = json.getBoolean("project_created"),
            remoteHintPresent = json.getBoolean("remote_hint_present"),
            remoteConfigured = json.getBoolean("remote_configured"),
            remoteError = json.optStringOrNull("remote_error"),
            pullStatus = json.getString("pull_status"),
            pullError = json.optStringOrNull("pull_error"),
        )
    }

    private fun requireContext(): Long {
        val handle = contextHandle
        check(handle != 0L) { "HolderNative.initialize() must be called first" }
        return handle
    }

    private fun parseProject(json: JSONObject) = HolderProject(
        projectId = json.getString("project_id"),
        name = json.getString("name"),
        gitRemoteUrl = json.optStringOrNull("git_remote_url"),
        privacyMode = json.getString("privacy_mode"),
    )

    private fun parseCard(json: JSONObject) = HolderCard(
        cardId = json.getString("card_id"),
        projectId = json.getString("project_id"),
        title = json.getString("title"),
        parentCardId = json.optStringOrNull("parent_card_id"),
        createdAt = json.getLong("created_at"),
        updatedAt = json.getLong("updated_at"),
        sortKey = json.getDouble("sort_key"),
        deletedAt = json.optLongOrNull("deleted_at"),
    )

    private fun parseOutgoingLink(json: JSONObject) = HolderOutgoingLink(
        toCardId = json.getString("to_card_id"),
        toType = json.getString("to_type"),
        kind = json.getString("kind"),
        label = json.optStringOrNull("label"),
        toTitle = json.optStringOrNull("to_title"),
    )

    private fun parseBacklink(json: JSONObject) = HolderBacklink(
        fromCardId = json.getString("from_card_id"),
        kind = json.getString("kind"),
        label = json.optStringOrNull("label"),
        fromTitle = json.optStringOrNull("from_title"),
    )

    private fun parseCardRef(json: JSONObject) = HolderCardRef(
        cardId = json.getString("card_id"),
        title = json.getString("title"),
    )

    private fun parseMilestone(json: JSONObject) = HolderMilestone(
        milestoneId = json.getString("milestone_id"),
        cardId = json.getString("card_id"),
        startAt = json.getLong("start_at"),
        endAt = json.optLongOrNull("end_at"),
        allDay = json.getBoolean("all_day"),
        kind = json.optStringOrNull("kind"),
        description = json.optStringOrNull("description"),
        createdAt = json.getLong("created_at"),
        updatedAt = json.getLong("updated_at"),
        cardTitle = json.optStringOrNull("card_title"),
    )

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (isNull(name)) null else getString(name)

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (isNull(name)) null else getLong(name)

    /** Mirrors holder-daemon's Bootstrap.cpp: first line, only if it's a markdown heading. */
    private fun deriveWelcomeTitle(content: String, fallback: String): String {
        val firstLine = content.substringBefore('\n').trimStart(' ', '\t', '\r')
        if (firstLine.isEmpty() || firstLine[0] != '#') return fallback
        val title = firstLine.trimStart('#', ' ', '\t')
        return title.ifEmpty { fallback }
    }
}
