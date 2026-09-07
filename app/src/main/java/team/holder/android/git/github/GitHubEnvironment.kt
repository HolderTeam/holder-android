package team.holder.android.git.github

import team.holder.android.BuildConfig

/**
 * The one place production vs. development picks a different GitHub App / relay deployment --
 * see GITHUB_SETUP_SERVICE_PLAN.md's "Production and development need fully separate
 * deployments" for why. Keyed on [BuildConfig.DEBUG] rather than a Gradle product flavor: the
 * existing `debug`/`release` buildType split already gives every debug build its own
 * `applicationId` (`.debug` suffix) and its own fixed signing certificate (see
 * `app/build.gradle.kts`'s `debug` signing config comment) for exactly this kind of
 * per-build-type external-identity reason, precedent already established for the Google Drive
 * OAuth client.
 *
 * `OAUTH_CALLBACK_URL`'s host must match `app/build.gradle.kts`'s `manifestPlaceholders
 * ["oauthHost"]` for the corresponding buildType -- both are derived from the same relay
 * deployment, but there's no single source of truth linking a Kotlin constant to a manifest
 * placeholder, so a change to one must be mirrored in the other by hand.
 */
internal object GitHubEnvironment {
    /** Holder Project Setup / Holder Project Setup Development's public, non-secret client_id. */
    val CLIENT_ID: String = if (BuildConfig.DEBUG) "Iv23liFvueeHnGLLdbRz" else "Iv23linTTVwa07QTSWAB"

    /** holder-github-service's relay base URL -- see that repo's wrangler.jsonc routes. */
    val RELAY_BASE_URL: String = if (BuildConfig.DEBUG) "https://setup-dev.holder.ws" else "https://setup.holder.ws"

    /** This App's own fixed callback -- exchange only, never client-supplied. Must match this
     * GitHub App's registered Callback URL exactly (wildcard matching is off). */
    val OAUTH_CALLBACK_URL: String = if (BuildConfig.DEBUG) {
        "https://auth-dev.holder.ws/android/oauth-callback"
    } else {
        "https://auth.holder.ws/android/oauth-callback"
    }

    /** The bare, un-scoped public listing page -- never an `/installations/new` deep link with
     * a baked-in `target_id`. See GITHUB_INTEGRATION_ANDROID_PLAN.md's "What's already
     * verified" section for why a scoped deep link can permanently misroute with no account
     * picker shown at all. */
    val APP_URL: String = if (BuildConfig.DEBUG) {
        "https://github.com/apps/holder-project-setup-development"
    } else {
        "https://github.com/apps/holder-project-setup"
    }
}
