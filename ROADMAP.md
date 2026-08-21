# Holder

Holder is a new card management application designed to let users follow their own workflow rather than imposing one methodology. It can support Zettelkasten, wiki-style note systems, the Snowflake Method, personal knowledge management, research projects, family/shared projects through Git sync, and other card-based workflows.

It's an ecosystem with a cross-platform GTK desktop backend. As well as a Beast-powered HTTP API backend and a command line tool (holderctl) for advanced users who want to access it through their own tools.

We have split out the core logic into libholder in the repo holder-core. So the phone apps can call libholder directly through a C ABI.

For Android, the first goal is not desktop feature parity. The phone should make Holder useful wherever the user is: create, read, edit, search and sync cards, while keeping the core data model in `libholder`.

A persistent search bar can sit at the top of many primary views. Most other interactions can use full-screen phone views rather than trying to reproduce the desktop layout.

### Step one — prove the core

Integrate `libholder` from the `holder-core` repository into the Android build and establish the Kotlin → JNI → C ABI → `libholder` boundary.

On first launch, create a default **Home** project and a welcome card. This gives a new user something immediately usable and exercises real project/card creation rather than only opening an empty database.

The milestone here is:

```text
Install Holder
    ↓
Home project exists
    ↓
Welcome card exists
    ↓
Both come from libholder
```

### Step two — usable standalone Holder

Build the essential navigation and card workflow:

#### 2.1

- Project list
- Card list for a project
- Card view

#### 2.2

- Card create/edit
- Basic project creation/rename/delete
- Basic card creation/delete

#### 2.3

- Persistent/global search bar where appropriate
- Search results

At the end of this step, someone should be able to use the Android app **without owning a desktop computer** to write cards. It is already a legitimate Holder client, just without all the advanced features.

### Step three — Git sync

Add Git-backed project synchronisation:

- Configure a remote repository
- Authentication/secret storage
- Encryption/recovery-key setup where applicable
- Pull/push/sync status
- Conflict/error handling
- Recovery/reset workflow

We should be able to write cards on the desktop, then see them on the phone,
and vice-versa.

### Step four — complete the normal Holder data model

Add the less frequently used but still core project-management functionality:

- Trash bin and restore/permanent-delete workflow
- Connections editor
- View and edit card relationships

This is where Android starts representing the same underlying Holder world as desktop rather than only cards and projects.

Dedicated "project settings" turned out to be mostly already covered by
existing screens (rename/delete on the project list, remote/encryption/
recovery on Git Sync) rather than a real gap — skipped for now rather than
building a settings screen with nothing new to put in it.

### Step five - desktop-created extended content

Once Git sync works, the Android app can also expose Holder content types created elsewhere.

For **AI threads**, the first Android version does not need to run AI at all. It only needs to understand and render synced conversations produced by desktop Holder. Initially they can be **read-only**, reusing much of the card-reading UI.

That gives a useful distinction:

```text
Desktop Holder
    local AI
       ↓
AI thread stored in project
       ↓
Git sync
       ↓
Android Holder
    read conversation
```

So Android users still benefit from desktop AI without us taking on Android model/runtime distribution yet.

### Step six — make device setup effortless

Once manual Git configuration works reliably, automate provisioning between Holder installations.

**Desktop → phone:** desktop generates a QR code containing the configuration required to add a project to the phone. The Android app scans it, validates it, stores the required secrets securely, and adds the project.

**Phone → desktop:** generate a portable configuration object that can be transferred by email/share sheet/file and imported by desktop Holder.

This stage should turn what might otherwise be a fairly technical Git/encryption setup into:

```text
Desktop
   ↓
Scan QR
   ↓
Project appears on phone
```

or:

```text
Phone
   ↓
Share Holder setup
   ↓
Open on desktop
   ↓
Project appears
```

We explicitly keep **local AI generation, model downloads, llama.cpp and Gemini Nano out of this roadmap for the first Android release**.
They can become a later `libholder-ai` project once the thing that materially improves Holder — having your cards in your pocket — is shipped.

### Step 7: support project Resources

This requires some wider cross-repo work to make resources a useful thing, so let's so this last.

- Project resources
- Resource viewing/opening

### Step 8: project-level views

Android is now close to desktop parity for working with a single card, but
desktop gives the user many different ways to see a *whole project* at once
that Android has no equivalent of yet:

- Flowboard — the hierarchical card-tree/board view, and how parent/child
  and next/previous ordering actually get edited on desktop (Android's
  Connections screen only shows hierarchy read-only right now).
- Connections graph — desktop's project-wide connections tool is a graph
  you navigate visually; Android only has the card-scoped list view built
  in Step four.

Not scoped yet. Likely needs its own design pass for what a
"whole-project view" should look like on a phone screen rather than a
straight port of desktop's graph/board widgets — noting it here so it
isn't lost.

### Step 9: fenced code blocks in the editor

The editor's live Markdown highlighter (`HolderMarkdownEditor.kt`) re-scans
the buffer on every keystroke with a regex per token type, but has no
concept of a fenced (` ``` `) code block today — only single-line inline
code. Two things fall out of fixing that:

- A correctness bug: bold/italic/etc. regexes currently fire *inside*
  fenced blocks (e.g. `some_function_name` renders as italic), since
  nothing suppresses them there.
- A prerequisite for spell-check (Step 10) to skip code content.

Language-aware syntax highlighting *inside* fences (e.g. Python keywords/
strings/comments) is a further, separate step worth doing at some point —
not scoped yet. Lean toward a small hand-rolled regex-per-language-token
table in the same style as the existing Markdown highlighter, rather than
a tree-sitter dependency (real grammar parsing would mean a new native
dependency, JNI bindings, and packaging a grammar per language — a much
bigger lift than anything else in this app). Worth skimming existing small
Compose syntax highlighters for technique, without taking them on as a
dependency.

### Step 10: spell-check in the editor

Android already gets IME autosuggest for free; this is about surfacing
*existing* misspellings, not just suggesting as you type.

- Hook `TextServicesManager`/`SpellCheckerSession` (via
  `getSentenceSuggestions`, not the older per-word API) into the same
  `OutputTransformation` pass the Markdown highlighter already uses,
  decorating flagged ranges with a plain underline (not a true squiggle —
  Compose has no built-in wavy-underline decoration, and a real one would
  need custom canvas drawing per flagged word; not worth it for a first
  version).
- Tap a flagged word to see replacement suggestions.
- Skip spellcheck inside code spans/fenced blocks (Step 9) and link
  destinations, but *do* check a Markdown link's visible text and a
  wikilink's page name — both are real prose/titles, not syntax.
- Needs debouncing, same idea as the existing card-search debounce
  elsewhere in the app — spell-check queries are async and shouldn't fire
  on every keystroke.
