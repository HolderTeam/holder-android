# Device Setup Plan (ROADMAP Step Six)

This is the real plan behind ROADMAP.md's "Step six — make device setup
effortless." **MVP scope is Desktop → phone only:** scan a QR code,
project appears. That's the direction that actually matches the story
this feature exists for — a user who has already been walked through
Git sync on desktop by its wizard now wants their cards on their phone,
without learning anything about Git, SSH, or encryption to get there.

The reverse direction, Phone → desktop (share a setup file/link, import
on desktop, for whichever device the user set Holder up on first), is
designed in full below but explicitly **deferred, not MVP** — kept in
this document because the design work is done and the two share most of
their underlying machinery, not because it's scheduled. Worth noting for
the record: it's actually the *cheaper* direction to build (see its
section below — zero new dependencies, pure UI wiring of calls that
already exist), so "easiest to implement" alone would point at doing it
first. It's cut from MVP anyway because it doesn't serve the actual
motivating case — a phone-first MVP would help someone whose *first*
device was their phone, which isn't the story Step six was written for.

Per `holder-daemon/docs/PRIVACY.md`, shielding non-technical users from
Git's power is already the whole point of Holder's encryption model:
"spreading the awesome power of Git to non-technical users needs to come
with a guardrail." Device setup is where that philosophy either holds up
or falls apart: if adding a phone still requires understanding what a
"deploy key" is, we haven't actually removed the guardrail-shaped hole,
we've just moved it later.

## What already exists — don't rebuild this

More of this is already built than Step six's ROADMAP entry suggests.
The **recovery token** mechanism is implemented end-to-end, cross-platform,
today:

```text
holder-core   src/privacy/ProjectPrivacy.{h,cpp}
                  export_recovery_token(project_id, project_key_id, pin,
                                         project_name?, git_remote_url?)
                  import_recovery_token(repo, project_id, pin, token_json, ...)
                  inspect_recovery_token(pin, token_json)   -- peek without importing

              include/holder/holder.h  (C ABI)
                  holder_recovery_token_export
                  holder_recovery_token_import
                  holder_recovery_token_inspect
                  holder_recovery_token_import_global   -- "device setup path":
                      creates the project first if this device has never
                      seen it, then pulls its remote if the token has one

holder-daemon POST /projects/{id}/recovery-token/export   {pin}
              POST /recovery-token/import                 {pin, recovery_token}

holder-desktop RecoveryController.export_recovery_token / import_recovery_token
               RecoveryKeyToolView: Email / Save-to-USB / Import buttons

holder-android HolderNative.exportRecoveryToken / importRecoveryToken /
                            inspectRecoveryToken / importRecoveryTokenGlobal
               GitSyncScreen: PIN field + "Export recovery token" button,
                              copy-to-clipboard (no scan/import UI yet)
```

Notably, desktop's `RecoveryController.import_recovery_token(pin, payload)`
already calls the daemon's `POST /recovery-token/import` — the *global*,
device-setup-capable endpoint (no existing project required), same as
Android's `importRecoveryTokenGlobal`. So desktop can already import a
token for a project it's never seen before; it's just missing an entry
point for "here's a token from my phone" and, same as Android, has no
answer yet for the Git-auth gap below.

The token itself (`ProjectPrivacy.cpp`) is a JSON envelope:

```text
{
  "version": 1,
  "kdf": { "name": "PBKDF2-HMAC-SHA256", "iterations": 210000, "salt_b64": "..." },
  "cipher": { "name": "holder-privacy-envelope-v1", "wrapped": "<base64 ciphertext>" }
}
```

wrapping a PIN-derived-key-encrypted inner payload:

```text
{ "project_id", "project_key_id", "project_key_material_b64",
  "project_name", "git_remote_url", "exported_at" }
```

`project_key_material_b64` is the project's 32-byte symmetric content key
(`kPrivacyKeyBytes`), the thing that actually decrypts card bodies. So a
recovery token, correctly imported, already gives a device everything it
needs to **read and decrypt** a project's cards, and already carries a
remote-URL hint the importing device can configure automatically.

**This is most of Step six already.** What's missing is turning "paste
this long string and remember a PIN" into "scan a code," and — the part
that actually matters — solving Git authentication, which the recovery
token deliberately does not touch.

## The actual gap: Git authentication

The recovery token carries the *encryption* key and the remote *URL*. It
does not carry anything that lets the importing device *push or pull*
against that remote. That's a separate, still fully manual step today:

```text
holder-android/app/.../git/GitIdentity.kt
```

generates a non-exportable AndroidKeyStore EC key per device and exposes
it as `sshPublicKeyLine()` — "the pasteable ecdsa-sha2-nistp256 AAAA...
line, e.g. for a GitHub deploy key." `GitSyncScreen` shows this line with
a copy button under "Device SSH key." The user is expected to go to
GitHub (or GitLab, or wherever), find the repo's deploy-key settings, and
paste it in themselves.

That is the actual "you need to understand Git" moment left in the
product. A QR flow that only wraps the existing copy/paste recovery-token
exchange in a scanner, and leaves this step exactly as manual as it is
today, hasn't solved Step six — it's solved the easier half of it. This
plan treats Git auth as the primary design problem, not an afterthought.

The gap is symmetric, not phone-specific: desktop generates its own
per-device SSH key too (`GitSyncController.generate_ssh_key(email,
key_path)` in `holder-desktop/src/controllers/git_sync.vala`), pasted
into the remote host's deploy-key settings the same manual way. So a
brand-new desktop being paired from an existing phone hits the identical
"whose key is trusted?" wall as a brand-new phone being paired from
desktop — the design below applies to both directions unchanged, just
with which device is "new" swapped.

## Design decisions

### 1. QR payload: reuse the recovery token verbatim

Don't invent a new wire format. The QR code's content should just be the
existing `recovery_token` string returned by `export_recovery_token` /
`holder_recovery_token_export`. It already contains everything (`project_id`,
`git_remote_url`, the key material), it's already versioned
(`"version": 1`) and self-describing (`cipher.name ==
"holder-privacy-envelope-v1"`), so a scanner can sanity-check "this looks
like a Holder pairing code" and give a friendly error on garbage QR
content, rather than crashing.

**PIN handling:** use a fresh, short-lived *pairing* PIN generated for
this QR, not the user's long-term recovery PIN. Reusing the long-term
recovery PIN would mean every phone pairing depends on the user
remembering (or having written down) the same PIN they'd need for full
disaster recovery, at exactly the moment — first phone setup — they're
least likely to have it handy. A fresh 6-digit PIN, generated by desktop
and displayed next to the QR code, typed once into the phone, is a much
better fit and requires no new core API (still just `export_recovery_token(...,
pin)` / `importRecoveryTokenGlobal(pin, token)` with an ephemeral `pin`).

**Size:** nobody has measured the actual `recovery_token` byte length for
a real project. It's plausibly a few hundred bytes once you account for
two UUIDs, the 44-char base64 key material, the remote URL, JSON
structure at two nesting levels, and base64 overhead on the encrypted
inner payload. That's within QR's capacity but likely a visually dense
code, not a trivial one. Before committing to "QR contains the whole
token," measure it for real, and if it's uncomfortably large for
reliable phone-camera scanning: drop `project_name` from the QR-specific
export (cosmetic only, not needed to import), and pick error-correction
level L/M rather than the higher levels QR defaults often use.

### 2. Git authentication: GitHub-only full automation for MVP, fallback for everything else

Decision (per direct steer): scope v1 to **making GitHub work with zero
manual steps**, and accept a manual fallback for every other host rather
than delaying on host-agnostic support. Three shapes were considered;
here's why the GitHub-specific one wins for MVP and what it actually
costs.

**Rejected — shared HTTPS credential.** Desktop mints a scoped credential
(fine-grained PAT, GitLab project access token) and embeds it in the
token; the phone authenticates over HTTPS instead of SSH, no key
registration needed. Dropped because **minting a fresh scoped credential
programmatically isn't generally possible** — fine-grained PAT creation
on GitHub is a web-UI flow, not something an authenticated `gh` CLI
session can trigger. The realistic fallback would be reusing desktop's
*own* credential for every paired phone, so one lost phone leaks access
equivalent to the desktop itself — losing a phone is exactly the ordinary
event this feature has to handle gracefully, not undermine.

**Option B — manual fallback, kept for everything GitHub doesn't cover.**
The phone generates its own Keystore-backed SSH identity (already built,
`GitIdentity.kt`), the app treats "pull fails, this device isn't trusted
yet" as an expected first-class state, and shows a copy-the-key screen —
with a deep link to the host's deploy-key settings when the host is
recognized, plain instructions otherwise:

```text
Scan QR → PIN entry → project + key material imported
    ↓
Pull attempt fails (expected: this device isn't trusted yet)
    ↓
"One more step" screen: this device's SSH key, a Copy button, and —
when the remote host is recognized (github.com, gitlab.com) — a deep
link straight to that repo's "Add deploy key" settings page
    ↓
User pastes, returns to Holder, taps Retry → pull succeeds
```

This is what non-GitHub hosts get (self-hosted Git, GitLab, Bitbucket) —
not removed, just demoted from "the v1 experience" to "the honest
fallback for the hosts we haven't automated."

**Option C (recommended MVP for GitHub) — desktop generates and
pre-registers the phone's key before the QR ever exists, so there's no
return channel to design.** The insight that makes this genuinely
different from the "6.4 stretch" idea in the previous draft of this
plan: the earlier framing assumed the *phone* generates its key and
something has to carry the public half back to desktop for registration
— a return channel this feature's one-directional QR doesn't have. But
nothing requires the phone to generate its own key. Desktop can generate
**and register** the deploy key entirely on its own, before the phone is
involved at all, then hand the *private* half to the phone inside the
same PIN-encrypted token as the project key material:

```text
Desktop: "Pair a phone" tapped
    ↓
generate_ssh_key(email, tmp_key_path)          -- already exists, git_sync_service.vala
    ↓
gh repo deploy-key add tmp_key_path.pub --repo owner/repo --title "..."
    (already shells out to `gh`: detect_github_cli_state / create_private_repo_and_verify
     in the same file establish this is a proven, already-used pattern)
    ↓
export_recovery_token(..., pin)  +  embed the new private key bytes
    ↓
Render QR + PIN
    ↓
Phone scans, imports token+key in one step -- pull succeeds immediately,
no "one more step" screen, no paste, nothing left to understand
```

Verified `gh repo deploy-key add <key-file> [--title] [--repo]` is real
and does exactly this (confirmed via `gh repo deploy-key add --help`
locally); `git_sync_service.vala` already runs `gh` as a subprocess for
`auth status`, `api user`, and `repo create`, so adding one more `gh`
invocation is consistent with existing code, not a new integration
pattern. One caveat `gh` prints itself and worth carrying into the UI:
*"If you de-authorize the GitHub CLI app or authentication token from
your account, any deploy keys added by GitHub CLI will be removed as
well"* — i.e. every phone paired this way stops syncing if the user ever
re-authenticates or revokes desktop's `gh` session. Worth a line of copy
on the pairing screen, not a blocker.

**The real cost: this only works in the Desktop → phone direction, and
it changes where a device's private key is born.** Two things worth
being explicit about rather than glossing over:

- *Not symmetric.* Desktop's half is cheap because `gh` CLI + an already
  logged-in session already exists there. Android has no equivalent —
  no GitHub-authenticated session concept anywhere in the app today, and
  no `gh` binary to shell out to. Doing the Phone → desktop direction
  this same fully-automated way would mean building a GitHub OAuth
  Device Flow login on Android from scratch, calling GitHub's REST API
  directly (`POST /repos/{owner}/{repo}/keys`) to register desktop's
  key. That's a materially bigger, separate feature — Phone → desktop
  stays on the Option B manual-paste experience (6.4) whenever it's
  eventually built; it isn't part of MVP at all, per the scoping above.
- *A transported key isn't the same guarantee as a device-generated one.*
  `GitIdentity.kt`'s existing model is a non-exportable AndroidKeyStore
  key — the private key material never exists outside secure hardware,
  full stop, by construction. A desktop-generated key that travels
  through a PIN-encrypted QR payload necessarily exists outside secure
  hardware at least momentarily (in desktop's process memory, in transit,
  briefly on the phone before it's stored). The phone should still
  **import the received key material into AndroidKeyStore on arrival**
  (`KeyStore.setEntry` with a `KeyProtection` spec supports importing raw
  key material since API 28, not just generating in place) so it gets
  hardware-backed protection from that point forward — but the moment of
  transport itself is a real, if brief and PIN-gated, departure from
  "this device's key has never existed anywhere else." State this
  plainly to the user pairing a phone this way rather than silently
  presenting it as equivalent to the self-generated model. `GitIdentity`
  needs a second code path (import a provided keypair) alongside its
  existing generate-your-own path — used for QR-provisioned GitHub
  pairing specifically, while manually-added projects and non-GitHub
  QR fallback keep generating their own key as today.

**Recommendation:** ship Option C for GitHub remotes as the MVP Desktop →
phone experience; Option B's manual-paste screen remains for every other
host and for the Phone → desktop direction entirely. Revisit full
automation for Phone → desktop, and for non-GitHub hosts, only once
there's a concrete reason to invest in a GitHub OAuth flow on Android or
a per-host API integration — not part of this MVP.

This scoping isn't just "GitHub is easier to automate," it's the right
cut for who actually needs it: someone who has deliberately picked a
different provider, or is running their own Git server, already has
enough Git literacy to handle a deploy-key paste — the population this
whole feature exists to remove hand-holding for and the population that
chose self-hosted git are largely disjoint. Option B isn't a lesser
experience being tolerated for those users; it's an appropriately-sized
one.

### 3. New dependencies

Neither repo has any QR code infrastructure today (checked: no
`libqrencode`/similar in `holder-desktop`'s meson build or `holder-core`'s
`vcpkg.json`; no ZXing/ML Kit/CameraX in `holder-android`'s
`libs.versions.toml`). Both sides need one:

- **Desktop:** a QR-generation library (e.g. `libqrencode`) as a new meson
  dependency.
- **Android:** a barcode-scanning library (ML Kit Barcode Scanning is the
  on-device, no-network option; ZXing/`zxing-android-embedded` is the
  other well-trodden choice — pick based on APK size and licensing, not
  covered by this plan) plus CameraX and the `CAMERA` permission.

The camera permission is worth calling out on its own: this is the first
feature in the Android app that needs it. For a product whose entire
pitch is "we protect you from your own oversharing," the permission
rationale screen deserves real copy explaining *why* — "to scan a setup
code from your desktop, nothing else" — rather than the OS's generic
prompt alone.

## Phone → desktop (deferred — not MVP)

Designed for completeness and because it shares almost all of its
machinery with the Desktop → phone direction above, but not scheduled —
see the note in the intro for why it's cut from MVP despite being the
cheaper build.

Same payload, same two-step shape (import project + key material, then
clear the Git-auth "one more step") — just carried differently, since a
desktop can't be assumed to have a camera to scan a phone's screen with.

**Transport: Android's share sheet, not a QR.** Phone generates a pairing
PIN and calls the existing `exportRecoveryToken(projectId, pin)` exactly
like the desktop side does, then hands the resulting token string to
Android's standard share sheet (`Intent.ACTION_SEND`, `text/plain`) —
the same system picker the user already sees for sharing anything else.
That covers email, a messaging app, saving to a file via "Save to
Drive"/"Save to device" targets, or AirDrop-equivalents, all through one
Android API rather than Holder reimplementing file-export/email
composition itself. Whatever channel carries it, the receiving end is
still just a token string plus a PIN the user has to bring across
separately (read it off the phone, said aloud, texted separately from
the token itself) — same PIN-is-out-of-band property as the QR side, see
Security notes.

**Desktop side:** a "Pair from phone" action next to (or replacing, once
this ships) the existing manual remote-URL entry, which is really just a
paste target plus a PIN field wired to the *already-implemented*
`RecoveryController.import_recovery_token(pin, payload)` — confirmed
above to already hit the device-setup-capable endpoint. No new desktop
core API needed here at all, only UI.

**Git-auth gap, same shape, roles reversed:** after import, a pull
attempt fails because *this* desktop's SSH key (`generate_ssh_key` in
`git_sync.vala`, already built) isn't a trusted deploy key yet. Desktop
gets the equivalent of Android's "one more step" screen: its own public
key, a copy action, and — same host-recognition idea — a link to the
remote's deploy-key settings when the host is recognized. This is new
desktop UI, but it's the same design already specified for 6.3 below,
just built on the desktop side of `git_sync_tool_view.vala` instead of
Android's `GitSyncScreen`.

Unlike Desktop → phone, this direction stays manual-paste even for
GitHub — the full-automation trick under "Git authentication" above
works because desktop can shell out to an already-authenticated `gh`
CLI; Android has no equivalent GitHub session to call the API with.
Automating this side too is 6.5, further out than this direction's own
base implementation (6.4) — and this whole direction is deferred, not
MVP, regardless.

**What this direction does *not* need:** no QR generation on desktop, no
barcode scanning on Android, no new dependency on either side — it's
pure UI wiring `RecoveryController`, `exportRecoveryToken`, and
`generate_ssh_key`/`GitIdentity` calls that already exist end-to-end.
That's what makes it cheaper than the MVP direction on pure build cost —
noted in the intro as the reason it's worth revisiting once MVP ships,
not a reason to build it first.

## Phased implementation

MVP is 6.1–6.3. 6.4 and 6.5 are deferred — real, designed, and worth
tracking, but not part of the first release.

**6.1 — Desktop: "Pair a phone" screen.** New action in Git Sync tooling:
generates a one-time pairing PIN. If the project's remote is a
`github.com` URL and `detect_github_cli_state()` reports an authenticated
`gh` session: run `generate_ssh_key` against a throwaway path, `gh repo
deploy-key add` the public half, and bundle the private key bytes into
the export alongside the usual `export_recovery_token(project_id,
project_key_id, pin, project_name, git_remote_url)` call (needs a new,
optional field on the token payload/C ABI for this — the existing
envelope format is versioned specifically so it can grow). Otherwise
(non-GitHub host, or `gh` not authenticated) fall back to exporting the
token without a key, same as before. Either way, render the result as a
QR code plus the PIN in large text. Needs the new `libqrencode`
dependency, and a small core-side change to carry the optional key field;
no new core *concept* — same token, same call, one new field.

**6.2 — Android: scan → import.** New screen: camera preview, barcode
scan, sniff-check the payload looks like a Holder token, PIN entry, call
`HolderNative.importRecoveryTokenGlobal(pin, token)` (already implemented,
currently has no UI caller at all). If the token carries a bundled
private key (6.1's GitHub path), import it into AndroidKeyStore as this
project's git identity and skip straight to a successful pull — no
device-authorization step needed. If it doesn't, fall through to 6.3.
Wire in a new entry point — the empty "no projects yet" state and/or a
menu action alongside the existing manual remote-URL entry in
`GitSyncScreen`. Needs the new barcode-scanning dependency, `CAMERA`
permission flow, and permission rationale copy.

**6.3 — Android: the device-authorization fallback.** For every case 6.2
didn't fully automate (non-GitHub host, or desktop's `gh` wasn't
authenticated at pairing time): detect the "project imported, but pull
failed because this device isn't trusted yet" case specifically
(distinguishable from other pull failures) and route to a dedicated
screen reusing `GitIdentity.sshPublicKeyLine()` plus a host-recognition
table for deep links (start with github.com, gitlab.com; anything else
gets the plain copyable key). Ends with a retry that re-attempts the
pull. **6.1–6.3 is the whole MVP.**

**6.4 — Deferred. Phone → desktop**, the full direction designed above:
Android "Pair a desktop" (pairing PIN, `exportRecoveryToken`, share
sheet) plus desktop's paste-token/PIN screen wired to the already-built
`RecoveryController.import_recovery_token`, plus desktop's own version
of 6.3's device-authorization screen built against `generate_ssh_key`.
Every call on both sides already exists; this phase is UI only, and
technically the cheapest of everything in this document — deferred
purely because it doesn't serve MVP's Desktop-wizard-first story, not
because anything about it is unresolved or hard.

**6.5 — Deferred, further out than 6.4.** Extend Option C-style full
automation to the Phone → desktop direction from 6.4. Needs a GitHub
OAuth Device Flow login on Android (nothing like it exists in the app
today) so the phone can call `POST /repos/{owner}/{repo}/keys` directly,
since there's no `gh` CLI equivalent to shell out to on Android. Don't
build this at all unless GitHub-remote Phone → desktop pairing turns out
to be common enough that 6.4's manual paste is a real friction point.

## Security notes

- A photo of the QR code alone doesn't compromise the project — the
  token is still PIN-wrapped. Someone who captures *both* the QR and the
  displayed pairing PIN (e.g. by photographing the whole screen) does get
  everything, but that's the same trust-your-immediate-surroundings
  property in-person QR pairing generally accepts elsewhere (e.g.
  WhatsApp Web) — worth stating explicitly rather than leaving implicit,
  not a flaw specific to this design.
- Nothing about the recovery-token mechanism enforces single-use or
  expiry today — it's a self-contained encrypted blob, not a
  challenge/response. A captured QR + PIN combination remains valid to
  replay later. Mitigating that (e.g. a short display window, or binding
  the export to a specific short-lived session) is an open question, not
  something this plan resolves — flagging it so it isn't silently assumed
  away.
- Option B's per-device SSH keys are individually revocable; that
  revocation is still a manual step for the user on the host's website
  after a lost phone, but at least doesn't require rotating a shared
  secret across every other already-paired device too. Option C's
  desktop-registered keys are exactly as individually revocable — same
  deploy-key-per-device model, just registered automatically instead of
  by hand — with one added wrinkle: `gh` itself warns that de-authorizing
  the GitHub CLI or rotating its token removes every deploy key it ever
  registered, not just the one being intentionally revoked. Worth
  surfacing that consequence in the desktop UI wherever a user might
  re-authenticate `gh`, not just in this document.
- Option C's transported private key exists briefly outside secure
  hardware (desktop process memory, in transit inside the PIN-encrypted
  token, momentarily on the phone before import) — a real, if brief and
  PIN-gated, departure from `GitIdentity.kt`'s existing guarantee that a
  device's key material never exists anywhere it wasn't generated. Land
  it in AndroidKeyStore immediately on import and don't retain a
  plaintext copy in memory longer than the import call needs; state the
  distinction plainly in the pairing UI rather than presenting it as
  equivalent to a self-generated key.
- The Phone → desktop share-sheet transport carries the same PIN-wrapped
  token as the QR side, just over a channel the user picked (email, a
  messaging app, a cloud drive) instead of an in-person camera scan. That
  channel is typically less ephemeral than a QR shown once on a screen —
  an email sits in an inbox indefinitely — so an intercepted message
  still only yields the token, not the separately-delivered PIN, but it's
  a materially longer-lived artifact than a QR code. Worth deciding
  whether the exported token should be *shown once and not persisted* by
  Holder's own share-sheet call (it can't control what the receiving app
  does with it), or whether that's an acceptable, disclosed tradeoff for
  the convenience.

## Open questions

- Real measured size of `recovery_token` for a representative project,
  and whether it needs trimming to scan reliably.
- Whether the UI should call this a "pairing code," distinct from the
  existing "recovery key" concept in `RecoveryKeyToolView`, so users
  don't conflate a short-lived setup PIN with the one they're told to
  keep somewhere safe for disaster recovery.
- ML Kit vs. ZXing for Android barcode scanning.
- Whether a GitLab/Bitbucket/self-hosted deep-link table is worth
  building for 6.3's first cut, or whether "GitHub gets a deep link,
  everyone else gets the plain instruction" is a good enough starting
  point to ship.
- Exact shape of the new optional key field on the recovery-token
  envelope/C ABI for Option C (raw private key bytes? Wrapped somehow
  beyond the existing PIN envelope?) and what deploy-key `--title` to
  register with (device name? "Holder — {project name}"?) so a user
  looking at GitHub's deploy-key list later can tell which key is which.
- What happens to the throwaway keypair `generate_ssh_key` writes to
  disk during 6.1's flow after it's been read into the token — delete it
  immediately, since desktop never needs to use it itself (only the
  *phone* authenticates with it going forward).
- Whether desktop should let a user un-pair a phone from the "Pair a
  phone" screen later (calling `gh repo deploy-key delete`), or whether
  that's left to the manual "go to GitHub and remove it" path even for
  Option C-paired devices — not designed here.

## Guiding principle

> Scanning a QR code, or sharing a setup file, should feel like the
> whole story. Anything the user still has to *understand* — not just
> tap through — after that is a bug in this plan, not an acceptable
> rough edge.
