# WhatsappFx — WhatsApp for agentic workflows

Records WhatsApp messages into a local journal and index, files attachments into a folder of your choice by
per-chat policy, and exposes the lot to AI coding agents through a command line.

Two processes. `wa-connector.exe` is Go and speaks WhatsApp (via [whatsmeow]) and nothing else;
`whatsappfx.exe` is Java and holds the index, the policy, the window and the write gate. They talk
NDJSON over a pipe. The split is deliberate: a crash in the protocol stack is a restart of a child,
not a dead JVM with the database open.

[whatsmeow]: https://github.com/tulir/whatsmeow

> **Read this before you link your number.** whatsappfx is an independent project, not affiliated with WhatsApp or
> Meta; "WhatsApp" is their trademark. It is an *unofficial* client: it joins your account as a linked device through
> whatsmeow, a reverse-engineered implementation of the WhatsApp protocol, not through any API WhatsApp offers. Using
> an unofficial client is against WhatsApp's terms, and WhatsApp can restrict or ban an account that uses one. Use it
> with a number you can afford to lose, and do not use it to send bulk or automated messages to people who did not
> ask for them: that is the behaviour bans are made for.

## Running it

`whatsappfx` must be running for the CLI to work — it is the process that holds everything. It lives
in the tray; Esc hides the window, the tray icon brings it back.

First run, pair the device: type the phone number in full international form and press **F2** for a
pairing code, or **F3** for a QR. On the phone: Linked Devices, Link a Device.

**F10** forgets the linked device, so this machine can be paired again. It takes two presses within five
seconds, because it ends a link only the phone can restore. The involuntary case needs nothing from anybody:
see *When the link breaks* below.

## Several numbers

One WhatsappFx holds several phone numbers, each like another e-mail account: its own window, its own name and colour, its own
linked device, journal and files, and one tray icon for all. Settings (F1), section "This number": **F2** names it, **F3** or a
click on a swatch gives it one of eight colours (no two numbers share one), **F4** adds another number, **F10** (twice) takes one off
the list; its messages and files stay on disk. A new number opens its Link window, and is linked from that phone like the first.

The window of a number wears its colour on its accent, its outgoing bubbles, its title bar and edge, and its taskbar icon (the icon
is tinted once there are two or more numbers); its notices carry its name and colour on their edge. A click on the tray icon opens the
chats, or, with several numbers, asks which one to open, or all. The right-click menu has an entry per number.

The command line is for one number at a time: `whatsappcli --account <key|name|end of its phone number> ...` (or the variable
`WHATSAPPFX_ACCOUNT`); without it, the first number. `whatsappcli accounts` lists them; two windows linked to the SAME phone number work (WhatsApp allows several linked devices) but are only for testing: the app warns, `accounts` shows `sameNumberAs`, and the command line then needs `--account <key>` to tell them apart; `account-add`, `account-set` and
`account-remove` change the list. An approval is for one number, so using a second number from a program asks again, and the
access log names the number on every line. A chat id and a message id belong to one number.

There is ONE session key per PC, not one per number: each number has its own encrypted session store under `accounts<key>session`,
and the one recovery code (`whatsappcli key --recovery-code`) restores the key of all of them. Adding a number makes no new code.

Each number runs its own small connector process (about 25 MB working set, 55 MB private, while idle and not yet linked; a linked
one holds more) and shares the one index, so memory grows by that, not by a second app.

## The command line

    whatsappcli status
    whatsappcli chats --contains alice
    whatsappcli messages --chat 1555xxxxxxx@s.whatsapp.net --limit 100
    whatsappcli search "invoice"
    whatsappcli media --state expired
    whatsappcli policy --chat 1555xxxxxxx@s.whatsapp.net --set on
    whatsappcli fetch --chat 1555xxxxxxx@s.whatsapp.net --id 3EB0...
    whatsappcli history --chat 1555xxxxxxx@s.whatsapp.net --count 50

Listings print TSV by default and JSON with `--json`, so a result goes into a spreadsheet or another
tool without a parser in between. To put a chat into a spreadsheet, redirect the TSV and open the file in a spreadsheet tool.

**A person approves every program that uses `whatsappcli`.** The first command a program runs makes the
app show a green window (the same approval window the leasegate gates use) that names the program, the
programs above it (the terminal, the agent that started it) and what it asks for. There are four tiers:
**list** (names of chats and contacts, status), **read** (messages: one chat, or every chat), **send**
(new messages and reactions, and edit and delete of your own messages, in one chat; it also reads there)
and **control** (stop the app, link or unlink this PC, change what it does). **F2** approves, **Esc**
refuses, and the command waits for the answer. The kernel names the program behind each connection, so
a command cannot claim to be someone else. An approval is for one linked number and one chat (or every chat,
for reading), ends when that program (the process it was given to) closes or at its time (sending to one
person 30 minutes and at most 20 messages, to yourself 4 hours, to a group 4 hours; reading one chat 4 hours,
every chat 1 hour; all in `Caps.java`), and stays valid when Windows is locked. Approving a program asks for no
password. `whatsappcli lock`, the tray menu and the settings window (**F5** lists approvals, **F6** revokes
them all) take approvals back. The app's own windows (chats, notification cards, settings) ask nothing: the
person is already there. A headless daemon has no window to ask in and refuses every command that needs an
approval; only `ping`, `show` and `notify-test` answer. Every allowed request is one line in `run/access.log`
(program, command, chat id and name, never the message text; a new file each day, 14 days kept, 2 MB at most
per file), and each send, edit and delete raises a notification.

**Groups, channels, status and broadcast lists are approved separately from a single chat**, in plainer words
("to MANY people at once"), because everyone in them sees what is sent; nothing is refused outright and no code
is needed. **Wallet open** (tray, Command-line access, F6) is the other way: for up to four hours every request of
every kind, groups included, passes with no green window; it is still written to `run/access.log` and every message
sent shows as a notice, the tray says it is on, and it can only be switched on in the app's own window.

`whatsappcli send --chat <jid> --text "..." --draft` sends nothing to that chat. It puts WhatsApp's own
click-to-chat link in your own chat (`https://wa.me/<number>?text=...`); tapping it on the phone opens that
person's chat with the text in the write box, to read, change and send by hand. A group has no number, so its link
is `https://wa.me/?text=...`, which opens WhatsApp with the text ready and lets you pick the chat (see
[How to use click to chat](https://whatsapp.com/faq/en/general/26000030)). The text is also written out in the
message and put in the write box of that chat in the app's window, unsent.

An unlock-code mechanism (a code the developer issues for one number and a written reason, checked by an Ed25519
signature) is built and tested, and is switched off here (`Unlocks.REQUIRED`).

The password file `write.pass` of earlier versions is no longer read; it can be deleted.

    whatsappcli react --chat <jid> --id <msgid>              # the configured bot emoji
    whatsappcli reply --chat <jid> --id <msgid> --text "..."  # quotes that message
    whatsappcli star  --chat <jid> --id <msgid>

For all three, the chat and the message id are the whole address: who sent it and whether it was
yours are read off the message row. That matters because an addressing mistake here is silent —
WhatsApp accepts a key for a message that does not exist and nothing appears in the chat. A reaction
is written back onto the message row, so *which bills have been marked* is a query, not a scroll
through the phone.

## Housekeeping

    whatsappcli autostart --set on    # a shortcut in the Startup folder
    whatsappcli reindex               # rebuild the index from the journal
    whatsappcli connect               # reconnect without restarting the app
    whatsappcli logout                # forget the linked device so this machine can pair again
    whatsappcli quit                  # stop cleanly

`autostart` is the one that matters: a linked device left offline for about a fortnight is
deregistered, and re-pairing costs another full history sync. It needs no administrator — a logon
task does, which is why this is a Startup shortcut, and Windows lists it under Settings → Startup
Apps like any other.

## Attachments

Off for every chat by default, because the destination may well be a shared or synced drive. Name
the chats you want with `policy --set on`, or list a name fragment under `mediaAllow` in `whatsappfx_settings.toml`.

The attachments folder is set in the settings window (**F9** picks a folder) or as `mediaFolder` in
`whatsappfx_settings.toml`; blank means `~\chatsgate\whatsappfx\media`.

Files land in `<attachments folder>\<account>\<chat>\<year>\` as
`<yyyymmdd_HHMM>_<last 8 of the message id>_<original name>`. Names are truncated to survive the
Windows path limit; the message id is never truncated, so a file on disk names exactly one message.

Each attachment carries a state: `none` not fetched, `queued` in the download queue right now, `done`
filed, `expired` gone from WhatsApp's servers, `failed` worth retrying, `toolarge` over
`maxAttachmentMb`. Only `queued` means work is pending — an attachment in a chat whose policy is off
sits at `none`, and a row left `queued` by a killed process is reset to `none` on the next start.

WhatsApp drops media from its servers after a short window. Past it, `media --state expired` lists
what could not be fetched — those can only come back from the phone, and only while the phone still
holds the file. Expiry is remembered once discovered, because it is learned by trying and no replay
of the journal can know it.

## Sounds

The new-message chime (two marimba notes) and the call ringtone are synthesised in code at run time, so this repository ships no sound file, and everything you hear by default is ours to publish. Turn either off from the tray menu.

To use a sound of your own, put a WAV file in the app's home: `~\chatsgate\whatsappfx\sound.wav` for the message chime, `ring.wav` for the ringtone. The app uses your file when it is there and the built-in sound otherwise. Delete the file to go back.

If you would rather hear the official WhatsApp sounds, that is your own choice on your own machine; we do not ship or link them. Once WhatsApp Desktop from the Microsoft Store is installed, its sounds are in `C:\Program Files\WindowsApps\5319275A.WhatsAppDesktop_<version>_x64__<id>\Sounds\` (`whatsapp_windows_pn_02.m4a` is the message sound, `whatsapp_windows_ringtone_02.m4a` the ringtone). They are M4A, and the app reads WAV, so convert them with ffmpeg:

```
$s = (Get-AppxPackage *WhatsAppDesktop*).InstallLocation + "\Sounds"
ffmpeg -i "$s\whatsapp_windows_pn_02.m4a" "$HOME\chatsgate\whatsappfx\sound.wav"
ffmpeg -i "$s\whatsapp_windows_ringtone_02.m4a" "$HOME\chatsgate\whatsappfx\ring.wav"
```

## Where things live

`~\chatsgate\whatsappfx\` (or `%WHATSAPPFX_HOME%`) — `whatsappfx_settings.toml`, `accounts.json` (the numbers: key, name, colour; a home from before there were several has one, `default`, where its data already is), `accounts\<key>\` (each number's session database, per-chat NDJSON journals and spool), the
ArcadeDB index, `wa.log`, and `bin\wa-connector.exe`. Nothing goes in a Drive-synced folder except
attachments: the sync client rewrites files underneath an open database.

The journal is the truth and the index is a rebuildable view over it. A corrupted database is a
`reindex`, not a data loss, and the journal is greppable without any of this code.

Two things are not in the journal and so do not come back from a reindex: per-chat attachment policy
and chat names. Re-state them with `policy --set on` and `names`.

## What is protected on disk

- **The linked device's keys are encrypted.** The session store (`accounts\<id>\session\whatsmeow.enc.db`) holds the device's identity and Signal keys: a plain copy of it could act as your linked device. Every page of it is encrypted (SQLite with the Adiantum encrypting file layer). The key is 256 random bits kept by your operating system: Windows DPAPI for your user account (`session.key` in the home), or your macOS login Keychain. A copy of the folder is useless on another machine or under another account.
- **Write down the recovery code.** `whatsappcli key --recovery-code` prints the key as 56 characters. A Windows password reset that does not know the old password, a new computer or a lost Keychain loses the key, and then the device has to be linked again. `whatsappcli key --restore <code>` puts it back.
- **Messages are not encrypted by the app.** The journals and the index are plain files, so they stay searchable and the index can always be rebuilt. If you want them encrypted at rest too, use your system's disk encryption where it has one. Attachments in the attachments folder are ordinary files too.
- **Not protected against** a program that runs as your own user while you are logged in: it can do whatever whatsappfx can. That is true of every desktop program.

An unencrypted session store from before this version is converted on the first start, checked, and the old file is kept as `whatsmeow.db.unencrypted-old` so you can delete it yourself once the app works.

## Reading the index directly

The listing verbs are built on SQL rather than on the typed query builder, and that is not a style
choice. ArcadeDB's native `Select` — which the builder uses — hands the result limit to the index
candidate scan, so the cap is spent before the remaining conditions and before the ordering. Measured
here: `messages --chat` returned 0 rows of 371, `search "the"` returned 11 of 662, and `chats`
reported a last-message timestamp ten days stale. The same questions in SQL answer correctly. Filed
upstream as ArcadeData/arcadedb#6565.

So if you extend this, ask the index in SQL and bound the result in Java. A limit inside the
statement puts the cap back where the engine can misuse it.

The index is opened embedded, through `DatabaseFactory`. It used to be an `ArcadeDBServer`, which
nothing here ever queried over the wire and which cost two listening sockets — an HTTP one from
2480-2489 and an HTTPS one from 2490-2499, so which port a WhatsApp archive answered on depended on
what else on this machine had already taken 2480 — plus thirteen threads, a plugin manager, a
security realm needing a root password invented for it, and about a second of startup. Measured: 23
threads and 1535 ms with the server, 10 threads and 603 ms without. If you ever want ArcadeDB Studio
against this index, stop the app and point a standalone server at
`~\chatsgate\whatsappfx\index\databases\wa_index` — the embedded lock is single-writer either way.

## Memory

The launcher sets `-Xmx384m` and it has to. With no `-Xmx` the JVM takes a quarter of physical RAM
as its maximum heap — 3.84 GB here — G1 expands into that whenever allocation is brisk, and by
default it never gives any of it back. This app was seen holding 2 GB over a 24 MB index for exactly
that reason. ArcadeDB compounds it: it derives its page-cache ceiling from `-Xmx` and, unbounded,
asked for 983 MB.

`vm.args` in `whatsappfx.jrc` also carries `-XX:G1PeriodicGCInterval=300000` with
`-XX:-G1PeriodicGCInvokesConcurrent`, which is what returns memory to the OS while the app sits idle.
The non-concurrent form matters: a periodic *concurrent* cycle reclaims the garbage without resizing,
so the heap stayed committed at 256 MB with 42 MB live in it. Measured with the full collection
instead: 304 MB committed down to 107 MB, RSS 336 MB down to 207 MB.

The other half was the cause rather than the ceiling. `Hub.status()` carries the attachment tally,
which is a grouped count over every message row — no index covers it, so it is a full scan — and the
window rebuilt its status block every two seconds and asked for it twice per rebuild. An idle app was
allocating about 13 MB/s and collecting 28 times a minute. The tally is now cached and invalidated by
the writes that move it, and the window stops refreshing while it is hidden, which for a tray app is
nearly always: 39 KB/s and twice a minute after.

If you add anything to `Hub.status()`, that is the constraint to respect — it is on a two-second loop
whenever somebody has the window open.

## Keeping the link alive

WhatsApp deregisters a linked device left offline for roughly a fortnight. The status pane shows how
long since WhatsApp last spoke to us and warns before that matters; running the app once a week is
enough.

## When the link breaks

WhatsApp ends a link when the account is banned, when somebody unlinks the device from the phone, or
after that fortnight offline. Recovery is automatic on this side and needs one thing from a person:

    whatsappcli status                          # loggedIn false, needsPairing true, and why
    whatsappcli pair --phone 1555xxxxxxx       # then enter the code on the phone

Nothing has to be reset by hand for that to work, and this is the whole reason the feature exists.
When WhatsApp removes a device, whatsmeow deletes it locally itself: `Store.Delete` nils the JID,
sets `Deleted`, and swaps every sub-store for one that answers `ErrDeviceDeleted`. The `Client` built
at startup keeps pointing at that corpse, `Connect` refuses on the flag before doing anything, and
pairing — the one command that has to work in exactly this situation — fails with *invalid use of
deleted device* for the life of the process. Measured here: the app sat in that state for two days
and only killing it cleared it.

There is no reviving a deleted device, so the connector builds a fresh one. It holds its client
behind an atomic pointer for that reason, and on `events.LoggedOut` it disconnects the old client,
drops the dead device and swaps in a new one without being asked — there is nothing left to protect
by then, and every command fails until a fresh device exists. `pair` re-arms on the way in too, so
that command can never be the one blocked by this.

`whatsappcli logout` is the deliberate version, for dropping a link that still works. It tries the
server unlink first and forgets the device locally whether or not the server answers, because
refusing to forget because a banned account will not reply is how a session gets stuck in the first
place. It needs a write approval, and `--local` skips the server call outright.

The window says all of this in words: the status pane reads `NOT PAIRED — <reason>, <how long> ago`,
a banner appears, and the tray says it once when it happens — the window is hidden nearly all the
time, which is how two days passed without anyone noticing.

Two things a re-pair does **not** cost. The journal and the index are a separate store and survive
it untouched. And the full history sync that follows does not rewrite the journal: a message already
recorded keeps its existing line and offset, so a relogin no longer doubles the truth-store.

## Building

    mvn install                       # the Java reactor
    cd connector && go build -o wa-connector.exe .

Go 1.26+, JDK 25. No C compiler: `CGO_ENABLED` stays 0 and the SQLite driver is pure Go. Copy the
connector to `~\chatsgate\whatsappfx\bin\` after building it.

## On a Mac

**Status, said plainly: it runs on a real Apple-silicon Mac (macOS 14, a CI runner), not yet on a person's own Mac.** On every manual run of the
"Mac" workflow (Actions tab) GitHub's Apple-silicon runner builds the connector and the app, starts the app on a scratch home, and checks:
the app starts and stays up, two numbers each get their own connector process, `whatsappcli` is identified by the real caller check (the
kernel's socket table) so `status` works, a command without `--account` is refused with several numbers, the chats, Settings and Link (QR)
windows open, a notification card appears, the "which number" chooser opens, a recolour works, the LaunchAgent is written and valid, and
quit stops everything; screenshots of each are kept with the run. What only a person at a Mac can tell: see "First things a Mac user meets" below.

What differs from Windows:

- The connector is `bin/wa-connector` (no `.exe`) in the app's home, `~/chatsgate/whatsappfx/`.
- Start at logon is a LaunchAgent, `~/Library/LaunchAgents/io.github.chatsgate.whatsappfx.plist`, which runs the
  launcher on PATH with `--background`. Turning it on writes the file and launchd reads it at the next login;
  it is listed under System Settings, General, Login Items. The launcher is looked for on PATH, then in
  `~/.local/bin`, `/opt/homebrew/bin` and `/usr/local/bin`.
- The program behind a `whatsappcli` connection is found by walking the macOS process table for the socket,
  as on Windows by the TCP table; macOS only lets a process look at its own user's, which is the case here.
  If it ever cannot be found, the command is refused rather than allowed.
- `daemon.json`, which holds the loopback token, is written readable by its owner only.
- The official WhatsApp app is opened with `open whatsapp://` and ended through its process, as
  `/Applications/WhatsApp.app/Contents/MacOS/WhatsApp`. Where its sound files are on a Mac was not checked, so
  the Sounds section above has no Mac recipe yet; your own `sound.wav` and `ring.wav` work the same.

To build for a Mac from any machine (Maven downloads the native bundle, so the build machine's own system
does not matter; use `clean` between targets, or an earlier shaded jar is shaded into the next):

    cd connector
    CGO_ENABLED=0 GOOS=darwin GOARCH=arm64 go build -trimpath -o wa-connector .      # Apple silicon; amd64 for Intel
    cd .. && mvn clean package -DskipTests -pl ui -am -Djavafx.classifier=mac-aarch64    # mac for Intel

Put `wa-connector` in `~/chatsgate/whatsappfx/bin/` and make it executable. `ui/target/whatsappfx.jar` is the
app (`java --enable-native-access=ALL-UNNAMED -jar whatsappfx.jar`) and `cli/target/whatsappcli.jar` the command
line. A downloaded binary on a Mac may need the quarantine flag cleared (`xattr -d com.apple.quarantine <file>`).
Native launchers for `whatsappfx` and `whatsappcli` on a Mac are not built yet: that needs a Mac runner, and is
not part of this change.

## Long-running work: jobs

Anything that can take a while — the attachment backfill today, more later — returns a job id straight
away rather than holding the connection open:

    whatsappcli fetch                       -> {"job":"media-backfill-...","queued":123,"total":123}
    whatsappcli job media-backfill-...      -> state, done/total, failed, skipped, bytes, eta
    whatsappcli job media-backfill-... --wait
    whatsappcli jobs

`--wait` polls and draws a progress line on **stderr**, so stdout still pipes clean JSON. The job record
lives in the index, not in memory: a dropped call or a restarted app does not lose it, and a job left
running by a killed process is marked `interrupted` on the next start rather than lying about being
alive. ETA is reported only where the total is genuinely known.

## Read-only SQL

    whatsappcli query "select mediaState, count(*) as n from WaMessage where mediaKind is not null group by mediaState"

SELECT and MATCH only. The typed verbs are the contract and stay the normal route; this is for the
questions they do not anticipate.

### The listing verbs do not print the stored column names

    whatsappcli columns                 # every type: what is stored, what is renamed, what is derived
    whatsappcli columns --type WaChat

A listing returns a presentation view and `query` returns storage, and until this was fixed nothing
said so. The message id is stored as `msgId` and every listing prints it as `id`, so a header copied
out of `messages` into SQL asked for a property that does not exist — and a missing property is
answered with a **column of nulls, not an error**. Measured before the fix: `select id, ts, text from
WaMessage` gave three rows with an empty id, and `where id = '<a real id>'` gave none at all, which
reads as *the message is not there*. That is not an engine bug and has nothing to do with
`arcadedb#6565`; documents are sparse and a missing field has to be legal.

`query` now resolves a printed name to the stored one before running, hands the column back under the
name you asked for, and says on stderr what it did. `WaJob.id` is a real stored property, so the
substitution is gated on the types the statement actually names and never fires there. Quoted text is
stepped over, so a search for a message containing the word *id* is untouched.

Where a printed column is derived rather than renamed there is nothing to rewrite, and the note
carries the query that produces it instead. The traps, all of which `columns` lists:

- `id` → stored as `msgId`; also the `--id` of `react`, `star`, `reply`, `fetch`
- `unread` → stored as `unreadCount`
- `name` on `WaContact` → not stored at all; the first non-blank of `fullName`, `businessName`, `pushName`, `firstName`
- `lastMsgTs` and `msgCount` on `WaChat` → stored, and **0 and empty on every row**, so they look like
  an answer. `chats` derives both from the messages: `select chat, max(ts), count(*) from WaMessage group by chat`

`ALIASES` in `Columns` is the single definition of the rename: `Queries` reads the same table when it
builds a listing, so the output and the map of it cannot drift. Every field name in that table comes
from the generated `$` constants — `WaMessage.$msgId`, `WaChat.$unreadCount` — so renaming a field in
the model is a compile error rather than a catalogue that quietly starts lying.

## Licence

Two licences, because the two programs are separate works that talk only through a pipe.

- **Everything outside `connector/`** (the Java app and command line): Apache-2.0, see `LICENSE`.
- **`connector/`** (`wa-connector.exe`): GPL-3.0, see `connector/LICENSE`. The connector is built with whatsmeow and
  go.mau.fi/util (MPL-2.0) and go.mau.fi/libsignal (GPL-3.0); a binary that contains a GPL-3.0 library is distributed
  under the GPL-3.0, with its source. Its other dependencies are MIT, BSD or Apache licensed; `connector/go.mod` lists them.
  The session store uses github.com/ncruces/go-sqlite3 (MIT) with SQLite itself (public domain).
- **Fonts**: the app bundles Roboto (Regular and Bold, unchanged), Apache-2.0, from github.com/googlefonts/roboto-3-classic; see `ui/src/main/resources/chatsgate/whatsappfx/ui/fonts/ROBOTO-LICENSE.txt`.
- **App icon**: the handset in the icon is the "whatsapp" glyph of Font Awesome Free 6.5.2 (fontawesome.com), licensed CC BY 4.0, Copyright 2024 Fonticons, Inc.; the ring around it is redrawn as a plain circle. `ui/icons/MakeIcons.java` draws every size and every account colour. The icon is not WhatsApp's logo, and this project is not affiliated with WhatsApp.

## Sending files and pictures

In a chat, **F6** (or the paperclip) chooses files; you can also **paste** a screenshot or copied files (Ctrl+V, Cmd+V on a Mac), or **drop** files on the chat. A small card above the write box shows what will go: a thumbnail for one picture, the names otherwise. **Enter** sends, **Esc** cancels, and whatever is in the write box goes as the caption of the first. Pictures go as pictures, everything else as a document. Voice and video calls are not supported: their buttons are struck through.

### First things a Mac user meets

- **F-keys are media keys on a Mac laptop.** The whole window is driven by F1 to F12; hold **Fn**, or turn on System Settings, Keyboard, "Use F1, F2, etc. keys as standard function keys". Every key on the bottom row is also a button you can click.
- **Install with curl, not a browser.** A file downloaded in a browser carries a quarantine flag and Gatekeeper refuses to open it ("cannot be opened because the developer cannot be verified"). `curl -L -o <file> <url>` leaves no flag. The app also clears the flag on its own connector before starting it. The app is not signed or notarised yet.
- **Notifications.** New messages show as WhatsappFx's own cards (windows on top of the others), which need no permission. They do not appear over a full-screen app. The few system notifications (link lost) come through macOS's notification centre and need notifications allowed for "java" (or the launcher) in System Settings, Notifications.
- **No menu-bar icon?** On a Mac with a notch the menu bar can hide it. Click the Dock icon: it asks which number to open. The app keeps running with no window open; quit it with Cmd+Q, the tray menu, or `whatsappcli quit`.
- **Software rendering in a VM.** In a virtual machine without a Metal GPU, JavaFX can crash at start with `NSInvalidArgumentException ... newArgumentEncoderWithLayout`; start with `-Dprism.order=sw`.
- **The menu bar may say "java"** when run as a jar. Start with `-Xdock:name=WhatsappFx -Dapple.awt.application.name=WhatsappFx`; the start-at-logon entry already does.
- Start at logon needs no launcher: with none on the PATH, the LaunchAgent runs the jar this app was started from.

### Installing on a Mac (first real install, 2026-11-04)

1. Get `whatsappfx-mac.tar.gz` from the "Mac" workflow (Actions, Run workflow; the `whatsappfx-mac` artifact holds the archive and its `.sha256`). It holds the app, the command line, the connector for Apple silicon and `install.sh`; nothing else.
2. Move it to the Mac without a browser (curl, or a copy over the local network), check `shasum -a 256`, then `tar -xzf whatsappfx-mac.tar.gz && sh whatsappfx-mac/install.sh`. It needs a Java 25 (`/usr/libexec/java_home -v 25`), installs under the home folder only (no sudo), and puts `whatsappfx` and `whatsappcli` in `~/.local/bin`.
3. `~/.local/bin/whatsappfx --background` starts it; with no number linked it opens the QR window. The first start creates the session key (a Keychain item): the app shows its recovery code once, to be written down.
- What was different from the runner: the Mac was reached through a shared cfucli shell whose invite had expired once (ask for a new one); the Mac could not pull from a Windows PC (the PC's firewall blocks inbound), but a push from the PC to a listener on the Mac (`nc -l 9000 > file`) worked; the shell of that session reports `x86_64` (it runs under Rosetta) while Java is arm64, which is fine.
