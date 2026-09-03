package team.holder.android.git.github

private val SCP_STYLE = Regex("""^git@github\.com:([^/]+)/(.+?)(\.git)?/?$""")
private val SSH_URL_STYLE = Regex("""^ssh://git@github\.com/([^/]+)/(.+?)(\.git)?/?$""")

/** Recognizes a GitHub SSH remote URL in either shape it might arrive in -- GitHub's own
 * `ssh_url` convention (`git@github.com:owner/repo.git`, scp-like, what
 * [GitHubConnection.ensureProjectRepo] itself produces) or the fully-qualified form
 * `GitSyncScreen`'s own placeholder shows (`ssh://git@github.com/owner/repo.git`, what a
 * hand-typed remote is more likely to use) -- and extracts `owner`/`repo` if it's a
 * github.com host. Returns null for anything else: a non-GitHub host, a malformed URL, an
 * HTTPS remote. Used by `RecoverProjectScreen` to decide whether a failed recovery pull is
 * eligible for the "register this device automatically" route (see
 * GITHUB_INTEGRATION_ANDROID_PLAN.md's wiring point 3) -- the manual `GitSyncScreen` path
 * remains available regardless for anything this returns null for. */
fun parseGitHubOwnerRepo(remoteUrl: String): Pair<String, String>? {
    val match = SCP_STYLE.matchEntire(remoteUrl) ?: SSH_URL_STYLE.matchEntire(remoteUrl) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}
