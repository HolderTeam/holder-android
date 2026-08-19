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

* Project list
* Card list for a project
* Card view

#### 2.2

* Card create/edit
* Basic project creation/rename/delete
* Basic card creation/delete

#### 2.3

* Persistent/global search bar where appropriate
* Search results

At the end of this step, someone should be able to use the Android app **without owning a desktop computer** to write cards. It is already a legitimate Holder client, just without all the advanced features.

### Step three — Git sync

Add Git-backed project synchronisation:

* Configure a remote repository
* Authentication/secret storage
* Encryption/recovery-key setup where applicable
* Pull/push/sync status
* Conflict/error handling
* Recovery/reset workflow

We should be able to write cards on the desktop, then see them on the phone,
and vice-versa.

### Step four — complete the normal Holder data model

Add the less frequently used but still core project-management functionality:

* Trash bin and restore/permanent-delete workflow
* Connections editor
* View and edit card relationships
* Project resources
* Resource viewing/opening
* Appropriate project settings

This is where Android starts representing the same underlying Holder world as desktop rather than only cards and projects.


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
