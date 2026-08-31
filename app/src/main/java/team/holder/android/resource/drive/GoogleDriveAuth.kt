package team.holder.android.resource.drive

import android.accounts.Account
import android.app.Activity
import android.content.Context
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.tasks.await

private const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"

/** The one thing Holder keeps locally about a connected Drive account. Never a secret --
 * see [team.holder.android.HolderSettings.driveConnectedAccountEmail] for where it's stored,
 * and [GoogleDriveAuth]'s own doc comment for why no token is stored alongside it. */
data class DriveAuthorization(val accessToken: String, val accountEmail: String?)

class GoogleDriveAuthException(message: String) : Exception(message)

/**
 * Google Drive OAuth for the narrow `drive.file` scope, via Play Services' Authorization
 * Client -- deliberately *not* Credential Manager, which handles sign-in/identity, not scope
 * authorization; requesting API access to a specific scope is exactly what the Authorization
 * Client is for. The Android OAuth client itself needs no client ID anywhere in this code --
 * Google resolves it from the app's own package name and signing certificate, which is what
 * registering the app in Google Cloud Console (package + SHA-1) was for.
 *
 * There is no refresh token to store, and nothing here writes to
 * [team.holder.android.keyring.AndroidKeyringStore]: once `drive.file` has been granted once,
 * calling [authorize] again transparently returns a fresh short-lived access token via Play
 * Services' own account/session handling -- no server, no manual refresh logic, no token
 * persisted by Holder at all. The only local state Holder keeps is which account is
 * connected (for UI display, and to skip the account picker on later calls) -- an ordinary
 * settings value, not a secret.
 */
object GoogleDriveAuth {
    /**
     * Requests (or silently renews) Drive access. If [accountEmail] is null, or Play
     * Services no longer has a live session for it, the user may be prompted for an account
     * and/or consent -- [resolveConsent] is called with the intent to launch in that case
     * (the caller supplies this via `ActivityResultContracts.StartIntentSenderForResult()`,
     * kept out of this object so it stays Compose/Activity-agnostic). Passing an
     * [accountEmail] that's already connected and still authorized never calls
     * [resolveConsent] at all.
     */
    suspend fun authorize(
        context: Context,
        accountEmail: String?,
        resolveConsent: suspend (IntentSenderRequest) -> ActivityResult,
    ): DriveAuthorization {
        val client = Identity.getAuthorizationClient(context)
        val requestBuilder = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
        if (accountEmail != null) requestBuilder.setAccount(Account(accountEmail, "com.google"))

        val initial = client.authorize(requestBuilder.build()).await()
        val resolved: AuthorizationResult = if (initial.hasResolution()) {
            val pendingIntent = initial.pendingIntent
                ?: throw GoogleDriveAuthException("Drive authorization needs a resolution but has no pending intent")
            val activityResult = resolveConsent(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            if (activityResult.resultCode != Activity.RESULT_OK) {
                throw GoogleDriveAuthException("Drive authorization was cancelled")
            }
            val data = activityResult.data
                ?: throw GoogleDriveAuthException("Drive authorization's resolution returned no data")
            client.getAuthorizationResultFromIntent(data)
        } else {
            initial
        }

        val accessToken = resolved.accessToken
            ?: throw GoogleDriveAuthException("Drive authorization did not return an access token")
        // toGoogleSignInAccount() is deprecated, but AuthorizationResult has no non-deprecated
        // way to read back which account was authorized -- it's used here purely for display
        // (Settings' "Connected as <email>"), never for anything security-relevant.
        return DriveAuthorization(accessToken, resolved.toGoogleSignInAccount()?.email)
    }
}
