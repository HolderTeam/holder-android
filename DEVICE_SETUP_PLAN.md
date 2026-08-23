# Device Setup Plan (ROADMAP Step Six)

This is the real plan behind ROADMAP.md's "Step six — make device setup
effortless," covering both directions it describes:

- **Desktop → phone:** scan a QR code, project appears — the primary
  case, since a user who has already been walked through Git sync on
  desktop by its wizard now wants their cards on their phone, without
  learning anything about Git, SSH, or encryption to get there.
- **Phone → desktop:** share a setup file/link (email, share sheet,
  file), import on desktop — the same problem with the devices' roles
  reversed, for whichever device the user set Holder up on first.

The two are close to mirror images of each other, and — as the next
section shows — most of the underlying machinery for *both* is already
built and shared between them. The main asymmetry is the transport:
phone-to-desktop can't assume desktop has a camera, so it carries the
same payload a different way (see "Phone → desktop" below) rather than
via QR.

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

### 2. Git authentication: two options, recommend the host-agnostic one first

**Option A — embed a scoped credential in the token, use HTTPS instead of SSH.**
Desktop mints a repo-scoped credential (a fine-grained GitHub PAT, a
GitLab project access token, etc.) and puts it in the encrypted payload
alongside the key material; the phone authenticates over
`https://x-access-token:<token>@host/owner/repo.git` instead of SSH, and
never needs its own registered key at all.

This is the "just works, one QR, done" option, but has a real problem:
**minting a fresh scoped credential programmatically isn't generally
possible** with just an authenticated `gh` CLI session — fine-grained PAT
creation on GitHub is a web-UI flow, not a CLI/API call a logged-in `gh`
session can trigger on your behalf. The realistic fallback is reusing
desktop's *own* existing credential for every paired phone, which means
one compromised or lost phone leaks access equivalent to the desktop
itself — a bad tradeoff for "make this foolproof for non-technical
users," since losing a phone is an ordinary event this has to handle
gracefully. It's also inherently GitHub/GitLab-shaped; Holder's remote
field is a freeform URL (`ssh://git@host/path/repo.git` placeholder in
`GitSyncScreen`), explicitly supporting self-hosted remotes with no API
to mint anything against.

**Option B (recommended baseline) — keep per-device SSH keys, automate
everything up to the paste.** The phone still generates its own
Keystore-backed SSH identity (already built, `GitIdentity.kt`) — nothing
here needs a new credential type or a new git transport path in
`holder-core`. What changes is the *experience* around the manual step:
after a successful QR import, the app knows the pull will fail until this
device is authorized, and treats that as an expected, first-class state
rather than a generic error:

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

This keeps every device individually revocable (lose a phone, remove one
deploy key, nothing else needs rotating), works identically for any git
host including self-hosted ones (unrecognized hosts just get the copyable
key and a plain instruction instead of a deep link), and reuses 100% of
already-built infrastructure. It doesn't fully hit "zero technical
steps," but it collapses "figure out what a deploy key is and where
GitHub hides that setting" down to "tap a link, paste, done" — which is
the part actually worth automating.

**Recommendation:** ship Option B for the first release. Revisit Option
A's GitHub-specific auto-registration only if Option B's one remaining
manual step turns out to be a real adoption blocker in practice — and
note that doing it properly (registering the *phone's* key from
*desktop*, automatically) needs the phone's public key to reach the
desktop somehow, which the one-directional QR flow described in
ROADMAP.md doesn't provide. That's a materially bigger feature (a return
channel: a second QR the phone shows and desktop scans, or a network
handshake) than "Step six" as scoped, not a small addition to it.

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

## Phone → desktop

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

**What this direction does *not* need:** no QR generation on desktop, no
barcode scanning on Android, no new dependency on either side — it's
pure UI wiring `RecoveryController`, `exportRecoveryToken`, and
`generate_ssh_key`/`GitIdentity` calls that already exist end-to-end.
That makes it, if anything, a cheaper first slice than the QR direction
— worth sequencing first if 6.1/6.2's new camera and QR dependencies
turn out to need more lead time than expected.

## Phased implementation

**6.0 — Phone → desktop (cheapest slice, no new dependencies).** Android:
"Pair a desktop" action that generates a pairing PIN, calls the existing
`exportRecoveryToken(projectId, pin)`, and hands the token to the share
sheet. Desktop: a paste-token + PIN field wired to the already-built
`RecoveryController.import_recovery_token`, followed by desktop's own
version of the 6.3 device-authorization screen (below), built against
`generate_ssh_key`/desktop's existing SSH identity instead of Android's.
All calls already exist on both sides; this phase is UI only. Given
that, and that it needs neither a QR library nor a camera permission,
consider shipping it before 6.1–6.3 rather than after.

**6.1 — Desktop: "Pair a phone" screen.** New action in Git Sync tooling:
generates a one-time pairing PIN, calls the existing
`export_recovery_token(project_id, project_key_id, pin, project_name,
git_remote_url)`, renders the returned token as a QR code plus the PIN
in large text alongside it. Needs the new `libqrencode` dependency; no
new core API.

**6.2 — Android: scan → import.** New screen: camera preview, barcode
scan, sniff-check the payload looks like a Holder token, PIN entry, call
`HolderNative.importRecoveryTokenGlobal(pin, token)` (already implemented,
currently has no UI caller at all). Wire in a new entry point — the
empty "no projects yet" state and/or a menu action alongside the existing
manual remote-URL entry in `GitSyncScreen`. Needs the new
barcode-scanning dependency, `CAMERA` permission flow, and permission
rationale copy.

**6.3 — Android: the device-authorization step.** Detect the "project
imported, but pull failed because this device isn't trusted yet" case
specifically (distinguishable from other pull failures) and route to a
dedicated screen reusing `GitIdentity.sshPublicKeyLine()` plus a
host-recognition table for deep links (start with github.com, gitlab.com;
anything else gets the plain copyable key). Ends with a retry that
re-attempts the pull.

**6.4 — Stretch, not required for first release.** GitHub-specific
auto-registration of the phone's deploy key when desktop's `gh` CLI is
already authenticated (reusing the same GitHub CLI integration
`GitSyncController`'s auto-sync flow already has) — blocked on designing
a return channel for the phone's public key to reach desktop, per the
note under Option B above. Track separately; don't let it hold up 6.1–6.3.

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
  secret across every other already-paired device too.
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

## Guiding principle

> Scanning a QR code, or sharing a setup file, should feel like the
> whole story. Anything the user still has to *understand* — not just
> tap through — after that is a bug in this plan, not an acceptable
> rough edge.
