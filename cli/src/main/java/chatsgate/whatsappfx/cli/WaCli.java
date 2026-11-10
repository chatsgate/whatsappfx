package chatsgate.whatsappfx.cli;

import com.google.gson.JsonObject;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import chatsgate.whatsappfx.wire.DaemonClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static chatsgate.whatsappfx.cli.Out.call;
import static chatsgate.whatsappfx.cli.Out.emit;

/**
 * Agentic access to WhatsApp. Every command needs a person to approve the calling program once, in the app's window.
 *
 * <p>Every listing prints TSV by default and JSON on request, so a result pipes into a spreadsheet
 * or into another tool without an intermediate parser.
 */
@Command(name = "whatsappcli", mixinStandardHelpOptions = true, versionProvider = WaCli.Ver.class,
        usageHelpWidth = 100,
        header = {
                "Read and act on WhatsApp through the running WhatsappFx app.",
                ""
        },
        description = {
                "The WhatsappFx app must be running: it holds the index, the WhatsApp session and the",
                "approval window. Start it with 'whatsappfx', check with 'status', stop it with 'quit'.",
                "",
                "ADDRESSING. Every chat is named by a JID, and there are three shapes:",
                "  1555xxxxxxx@s.whatsapp.net   a person, by phone number",
                "  12345678901234@lid            the same person, by linked-device id",
                "  1203634xxxxxxxxxxx@g.us       a group",
                "One conversation can appear under BOTH a @lid and an @s.whatsapp.net jid, as two rows",
                "with the same 'pn'. Do not assume one address is the whole chat. 'policy' writes to",
                "every address of a conversation; reads are per-jid, so use the jid the rows carry.",
                "Find a jid with: chats --contains <part of a name>",
                "",
                "SEVERAL NUMBERS. WhatsappFx can hold several linked phone numbers, each with its own window, name and colour.",
                "Every command is for ONE of them: --account <short name|name|phone number> (or the variable",
                "WHATSAPPFX_ACCOUNT). With one number the flag is optional; with several, a command without it is refused and the reply lists them, like cdpg and its profiles. 'accounts' lists them (no approval); 'account-add', 'account-set' and",
                "'account-remove' change the list. A chat id (JID) and a message id belong to one number: the same person",
                "is a different chat under each number. An approval is for one number: using a second asks again.",
                "",
                "TWO RESPONSE SHAPES, so nothing has to be guessed:",
                "  most verbs answer immediately with their result;",
                "  'fetch' and 'reindex' return {\"job\":\"...\"} at once — poll it with 'job <id>',",
                "  or block and watch progress with 'job <id> --wait' (progress on stderr, so stdout",
                "  still pipes clean).",
                "",
                "OUTPUT. Listings print TSV with a header row by default and JSON with --json, so a",
                "result goes into a spreadsheet or another tool without a parser. When a listing returns",
                "exactly as many rows as its --limit, it says so on stderr — that is a truncated answer,",
                "not a complete one. Raise --limit or narrow the query.",
                "",
                "APPROVAL. The first command a program runs makes the app show a green window naming that program, the",
                "programs above it (the terminal, the agent) and what it asks. There are four tiers: LIST (names of chats",
                "and contacts, status), READ (messages, one chat or every chat), SEND (new messages and reactions, and",
                "edit and delete of your own messages, in ONE chat; it also reads there) and CONTROL (stop the app, link or",
                "unlink, policy.set). A person approves with F2 or refuses with Esc, and the command waits for the answer.",
                "An approval is for one linked number, ends when that program closes or at its time (a SEND to one person",
                "30 minutes and 20 messages, to yourself 4 hours, a group 4 hours; reading one chat 4 hours, every chat 1 hour),",
                "and survives Windows being locked. 'lock' revokes every approval.",
                "",
                "A group, a channel, status or a broadcast list is approved separately from a single chat, in plainer words,",
                "because many people see what is sent there. DRAFTS: `send --chat <jid> --text ... --draft` sends nothing to that",
                "chat: it puts a WhatsApp link in your own chat that opens that chat with the text ready (for a group, or a chat",
                "with no known number, the link opens WhatsApp and you pick the chat), and you send it by hand on the phone. The",
                "text is also written out in that message and put in the chat window's write box.",
                "The tray's Command-line access window has WALLET OPEN: up to four hours with no green window for any request,",
                "still logged and announced. Every allowed request is one line in run/access.log (never the text), and each",
                "send, edit and delete raises a notice.",
                ""
        },
        footer = {
                "",
                "MARKING AND ANSWERING A MESSAGE",
                "  react --chat <jid> --id <msgid>              the configured bot emoji, or --emoji X",
                "  reply --chat <jid> --id <msgid> --text ...   posts a reply quoting that message",
                "  star  --chat <jid> --id <msgid>              private to your own devices",
                "The chat and the id are the whole address. Who sent it and whether it was yours are read",
                "off the message row, so do NOT pass --sender: getting it wrong does not fail, it builds a",
                "key for a message that does not exist and nothing appears in the chat. Message ids are the",
                "'id' column of 'messages' and 'media'. A reaction is written back onto the row, so",
                "\"which of these have I marked\" is a query.",
                "",
                "CALLS",
                "Every call event (offer, accept, terminate, reject) is a message row of type 'call', id",
                "call-<callId>-<state>. An incoming offer raises a tray notification. Answering is not possible.",
                "  call-reject --chat <jid> --id <callId|row id>   declines a ringing call (a write)",
                "",
                "SENDING FILES",
                "  send  --chat <jid> --file a.pdf                     one file",
                "  send  --chat <jid> --file a.jpg --file b.jpg ...    each --file is its own message;",
                "                                                      --caption (same option as --text)",
                "                                                      lands on the first file only",
                "  reply --chat <jid> --id <msgid> --file a.pdf        a media reply, quoting that message",
                "A local path is resolved relative to where you run the command, not the daemon. --as",
                "image|video|audio|voice|document|sticker overrides what the file extension suggests;",
                "voice notes and stickers are reachable only through it. Image, video and audio are capped",
                "at WhatsApp's own 16 MB — over that, the error names the size and says to add --as document,",
                "which has no such cap. A jpeg/png/gif gets a thumbnail and dimensions so the bubble and the",
                "chat-list preview render before the file has downloaded; a webp image sends without either,",
                "because nothing in this tool can decode webp. A sent file is filed onto the shared drive",
                "and recorded 'done' immediately, regardless of that chat's attachment policy — see media",
                "'state' below, which then covers outbound files as well as inbound ones.",
                "",
                "ATTACHMENTS are off for every chat by default, because the destination is a shared drive.",
                "  policy --chat <jid> --set on     then:  fetch --chat <jid>      (backfills the history)",
                "Each attachment carries a state:",
                "  none      not fetched — the normal state in a chat whose policy is off",
                "  queued    in the download queue right now; the only state that means work is pending",
                "  done      filed; localPath says where — a file this account sent is filed at once",
                "  expired   gone from WhatsApp's servers, recoverable only from the phone",
                "  failed    worth retrying              toolarge  over maxAttachmentMb in whatsappfx_settings.toml",
                "'fetch --chat X --id Y' downloads one file and ignores the size cap.",
                "Files land under the attachments folder (settings window, F9; default ~\\chatsgate\\whatsappfx\\media)\\<account>\\<chat>\\<year>\\.",
                "",
                "QUERYING BEYOND THE VERBS",
                "  query \"select mediaState, count(*) as n from WaMessage where mediaKind is not null",
                "         group by mediaState\"",
                "SELECT and MATCH only. Types: WaMessage, WaChat, WaContact, WaJob. Put no LIMIT in the",
                "SQL text — use --limit, which is applied on the way out. A LIMIT inside the statement is",
                "answered wrongly by the engine (ArcadeData/arcadedb#6565), which is also why every verb",
                "here is built on SQL rather than on the typed query builder.",
                "",
                "THE LISTING VERBS DO NOT PRINT THE STORED COLUMN NAMES, so a header copied into SQL can",
                "ask for a field that does not exist — and a missing field is answered with a column of",
                "nulls, not with an error. Run 'columns' for the whole map. The ones that bite:",
                "  id           printed by messages/search/media; STORED AS msgId. query rewrites it for",
                "               you and says so, so 'select id, text from WaMessage' now works.",
                "  unread       printed by chats; stored as unreadCount.",
                "  name         printed by contacts; WaContact stores no name — it is the first non-blank",
                "               of fullName, businessName, pushName, firstName.",
                "  lastMsgTs    WaChat.lastMsgTs is real and is 0 on every row, so it looks like an answer",
                "  msgCount     and is not. chats derives both from the messages:",
                "               select chat, max(ts), count(*) from WaMessage group by chat",
                "",
                "FIELDS WORTH KNOWING on WaMessage: account, chat, chatPn, sender, senderPn, msgId,",
                "fromMe, ts (unix seconds), pushName, text, source (live|sent|history), mediaKind,",
                "mimetype, filename, size, mediaState, localPath, localSha256, reaction, starred, joff.",
                "  'search' and 'messages --contains' match the text field only.",
                "  'type' is empty on most rows — whatsmeow fills it for live messages only. Do not filter",
                "  on it; use mediaKind for media and text for content. 'protocol' means encryption",
                "  housekeeping, not something a person said.",
                "",
                "EXAMPLES",
                "  whatsappcli chats --contains tejo",
                "  whatsappcli messages --chat 1555xxxxxxx@s.whatsapp.net --limit 200 > chat.tsv",
                "  whatsappcli media --state done --json | ...",
                "  whatsappcli fetch --chat <jid> && whatsappcli job <id> --wait",
                "",
                "WHEN THE LINK BREAKS. WhatsApp removes a linked device when the account is banned, when",
                "somebody unlinks it from the phone, or after about a fortnight offline. Nothing has to be",
                "repaired by hand for that: the connector throws the dead device away by itself and",
                "'status' then reports needsPairing true, with logoutReason and logoutAt saying what",
                "happened and when. All that is left is the half only a person can do:",
                "  whatsappcli pair --phone 1555xxxxxxx      then enter the code on the phone",
                "'logout' is the deliberate version of the same thing, for dropping a link that still works.",
                "It needs a write approval; '--local' skips the unlink request for an account that is banned",
                "or offline. A re-pair costs a full history sync, and the journal and index survive it.",
                "",
                "The app can be offline for about a fortnight before WhatsApp deregisters it, so keep",
                "'autostart --set on'. 'status' reports secondsSinceLastEvent for exactly this."
        },
        subcommands = CommandLine.HelpCommand.class)
public final class WaCli {

    /** The version shown by --version: the release this jar was built as (see wire.Version). */
    public static final class Ver implements CommandLine.IVersionProvider {
        @Override public String[] getVersion() { return new String[] {"whatsappcli " + chatsgate.whatsappfx.wire.Version.current()}; }
    }

    @Option(names = "--json", scope = CommandLine.ScopeType.INHERIT, description = "JSON instead of TSV")
    boolean json;

    /** Which linked number a command is for; without it, the first one (the only one until another is added). Also WHATSAPPFX_ACCOUNT. */
    @Option(names = "--account", scope = CommandLine.ScopeType.INHERIT, paramLabel = "<number>",
            description = "which linked number: its short name, the name you gave it, or its phone number (all of it, or the last 4+ digits); see 'accounts'. Optional with one number; with several, a command without it is refused and told what exists")
    void account(String key) {
        DaemonClient.account = key;
    }

    public static void main(String[] args) {
        DaemonClient.callerDir = System.getProperty("user.dir");
        DaemonClient.callerCommand = "whatsappcli " + String.join(" ", args);
        DaemonClient.account = System.getenv("WHATSAPPFX_ACCOUNT");
        // A stack trace is noise to the agent reading this and to the person reading the terminal;
        // the message is the whole answer.
        System.exit(new CommandLine(new WaCli())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((e, cmd, parse) -> {
                    cmd.getErr().println(e.getMessage() == null ? e.toString() : e.getMessage());
                    return 1;
                })
                .execute(args));
    }

    @Command(name = "status", description = "Connection, pairing and backlog state.")
    int status() throws Exception {
        emit(call("status", Map.of()), true);
        return 0;
    }

    @Command(name = "show", description = "Bring up a WhatsappFx chats window (maximized), optionally on one chat. With several numbers and none named, the app asks which.")
    int show(@Option(names = "--chat", paramLabel = "<jid>", description = "open this chat") String chat,
             @Option(names = "--window", paramLabel = "<settings>", description = "open that window instead: settings") String window,
             @Option(names = "--all", description = "open the window of every number") boolean all) throws Exception {
        emit(call("show", all ? Map.of("all", true) : window != null ? Map.of("window", window) : chat == null ? Map.of() : Map.of("chat", chat)), true);
        return 0;
    }

    @Command(name = "accounts", description = "The linked numbers: key, name, colour, phone number and whether each is linked and online. Name one with --account.")
    int accounts() throws Exception {
        emit(call("accounts", Map.of()), true);
        return 0;
    }

    @Command(name = "account-add", description = "Add another number: it opens in its own window, unlinked, ready for 'pair' (or the QR in its window). A new number needs your phone to link it.")
    int accountAdd(@Option(names = "--title", paramLabel = "<name>", description = "what you call it (its window and tray entry)") String title,
                   @Option(names = "--colour", paramLabel = "<name|#rrggbb>", description = "green, blue, red, amber, violet, cyan, pink or orange; default the first one no number wears") String colour) throws Exception {
        var m = new LinkedHashMap<String, Object>();
        if (title != null) m.put("title", title);
        if (colour != null) m.put("colour", colour);
        emit(call("account.add", m), true);
        return 0;
    }

    @Command(name = "account-set", description = "Rename the number given by --account, or change its colour. Its key and data never change.")
    int accountSet(@Option(names = "--title", paramLabel = "<name>", description = "the new name") String title,
                   @Option(names = "--short", paramLabel = "<name>", description = "its short name for --account: 1 to 16 lower-case letters, digits or dashes") String shortName,
                   @Option(names = "--colour", paramLabel = "<name|#rrggbb>", description = "the new colour; one no other number wears") String colour) throws Exception {
        var m = new LinkedHashMap<String, Object>();
        if (shortName != null) m.put("short", shortName);
        if (title != null) m.put("title", title);
        if (colour != null) m.put("colour", colour);
        emit(call("account.set", m), true);
        return 0;
    }

    @Command(name = "account-remove", description = "Stop the number given by --account and take it off the list. Its folder and messages stay on disk; adding the same key back finds them.")
    int accountRemove() throws Exception {
        emit(call("account.remove", Map.of()), true);
        return 0;
    }

    @Command(name = "who", description = "Who a number is: a LID or phone number (digits, @digits or a full jid) to its phone number and the name saved in your contacts (else the name they show). Groups mention people by LID.")
    int who(@picocli.CommandLine.Parameters(arity = "1..*", paramLabel = "<number|jid>") java.util.List<String> ids) throws Exception {
        emit(call("who", Map.of("ids", ids)), true);
        return 0;
    }

    @Command(name = "notify-test", description = "Show a test notification card in WhatsappFx: a message, or a ringing call (no chat behind it, nothing is sent).")
    int notifyTest(@Option(names = "--call", description = "a ringing call card instead of a message card") boolean call) throws Exception {
        emit(call("notify-test", Map.of("kind", call ? "call" : "message")), true);
        return 0;
    }

    @Command(name = "pair", description = "Link this machine to the phone. Works even after WhatsApp removed the last device.")
    int pair(@Option(names = "--phone", paramLabel = "<digits>", description = "full international number, digits only") String phone,
             @Option(names = "--qr", description = "print QR payloads instead of a pairing code") boolean qr) throws Exception {
        if (qr || phone == null) {
            emit(call("pair.qr", Map.of()), true);
            Out.STDOUT.println("Watch 'whatsappcli status' for pendingQr, or use the app window.");
        } else {
            emit(call("pair.code", Map.of("phone", phone)), true);
        }
        return 0;
    }

    @Command(name = "logout", description = "Forget the linked device so this machine can pair again.")
    int logout(@Option(names = "--local", description = "skip the unlink request and forget it here only — use when the account is banned or offline") boolean local) throws Exception {
        emit(call("logout", args("local", local ? true : null)), true);
        Out.STDOUT.println("Pair again with: whatsappcli pair --phone <digits>");
        return 0;
    }

    @Command(name = "connect", description = "Reconnect to WhatsApp without restarting the app.")
    int connect() throws Exception {
        emit(call("connect", Map.of()), true);
        return 0;
    }

    @Command(name = "disconnect", description = "Drop the WhatsApp connection, staying paired.")
    int disconnect() throws Exception {
        emit(call("disconnect", Map.of()), true);
        return 0;
    }

    @Command(name = "chats", description = "Conversations, most recent first.")
    int chats(@Option(names = "--contains", paramLabel = "<text>", description = "case-insensitive substring") String contains,
              @Option(names = "--limit", defaultValue = "100", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("chats", args("contains", contains, "limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "messages", description = "Messages in one chat, newest first.")
    int messages(@Option(names = "--chat", paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
                 @Option(names = "--contains", paramLabel = "<text>", description = "case-insensitive substring") String contains,
                 @Option(names = "--before", paramLabel = "<unixSeconds>", description = "only messages older than this") Long before,
                 @Option(names = "--limit", defaultValue = "50", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("messages", args("chat", chat, "contains", contains, "before", before, "limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "search", description = "Full-text search across every chat.")
    int search(@Parameters(index = "0", paramLabel = "<text>", description = "matches the message text only") String text,
               @Option(names = "--limit", defaultValue = "50", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("messages", args("contains", text, "limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "query", description = "Read-only SQL against the index. SELECT only.")
    int query(@Parameters(index = "0", paramLabel = "<sql>", description = "SELECT or MATCH over WaMessage, WaChat, WaContact, WaJob. No LIMIT in the text — use --limit. Column names: 'columns'.") String sql,
              @Option(names = "--limit", defaultValue = "500", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        var reply = DaemonClient.GSON.fromJson(call("query", args("sql", sql, "limit", limit)), JsonObject.class);
        // Stderr, so a note about a column name never lands in the middle of the data being piped.
        for (var note : reply.getAsJsonArray("notes")) Out.STDERR.println("note: " + note.getAsString());
        emit(DaemonClient.GSON.toJson(reply.get("rows")), json, limit);
        return 0;
    }

    @Command(name = "columns", description = "The real column names: what is stored, what a listing renames, what it derives.")
    int columns(@Option(names = "--type", paramLabel = "<WaMessage|WaChat|WaContact|WaJob>", description = "one type; every type by default") String type) throws Exception {
        emit(call("columns", args("type", type)), json);
        return 0;
    }

    @Command(name = "names", description = "Pull contact names and resolve chats stored under a LID.")
    int names() throws Exception {
        emit(call("names.sync", Map.of()), true);
        return 0;
    }

    @Command(name = "contacts", description = "Known names.")
    int contacts(@Option(names = "--contains", paramLabel = "<text>", description = "case-insensitive substring") String contains,
                 @Option(names = "--limit", defaultValue = "200", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("contacts", args("contains", contains, "limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "media", description = "Attachments and where they landed.")
    int media(@Option(names = "--chat", paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
              @Option(names = "--state", paramLabel = "<state>", description = "none, queued, done, expired, failed or toolarge; only queued means pending") String state,
              @Option(names = "--limit", defaultValue = "200", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("media", args("chat", chat, "state", state, "limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "fetch", description = "Download one attachment to the shared drive now.")
    int fetch(@Option(names = "--chat", paramLabel = "<jid>", description = "one chat; omit for every chat with policy on") String chat,
              @Option(names = "--id", paramLabel = "<msgid>", description = "one message; omit to backfill the chat, ignores the size cap") String id) throws Exception {
        emit(call("media.fetch", args("chat", chat, "id", id)), true);
        return 0;
    }

    @Command(name = "job", description = "Progress of one long-running job.")
    int job(@Parameters(index = "0", paramLabel = "<jobId>", description = "the id fetch or reindex returned") String id,
            @Option(names = "--wait", description = "poll until it finishes") boolean wait) throws Exception {
        while (true) {
            var json = call("job", Map.of("id", id));
            var state = DaemonClient.GSON.fromJson(json, com.google.gson.JsonObject.class).get("state").getAsString();
            if (!wait) {
                emit(json, true);
                return 0;
            }
            Out.STDERR.print((char) 13 + Out.progress(json) + "   ");
            if (!"running".equals(state)) {
                Out.STDERR.println();
                emit(json, true);
                return "done".equals(state) ? 0 : 1;
            }
            Thread.sleep(1000);
        }
    }

    @Command(name = "jobs", description = "Recent jobs, newest first.")
    int jobs(@Option(names = "--limit", defaultValue = "20", paramLabel = "<n>", description = "max rows; a full page is reported on stderr") int limit) throws Exception {
        emit(call("jobs", args("limit", limit)), json, limit);
        return 0;
    }

    @Command(name = "policy", description = "Whether a chat's attachments are downloaded.")
    int policy(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
               @Option(names = "--set", required = true, paramLabel = "<on|off|inherit>", description = "applies to every address of the conversation") String value) throws Exception {
        emit(call("policy.set", args("chat", chat, "policy", value)), true);
        return 0;
    }

    @Command(name = "history", description = "Ask the phone for older messages in a chat.")
    int history(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
                @Option(names = "--id", paramLabel = "<msgid>", description = "oldest message id already held") String id,
                @Option(names = "--ts", paramLabel = "<unixSeconds>", description = "its timestamp") Long ts,
                @Option(names = "--count", defaultValue = "50", paramLabel = "<n>", description = "how many older messages to ask for") int count) throws Exception {
        emit(call("history.more", args("chat", chat, "id", id, "ts", ts, "count", count)), true);
        return 0;
    }

    @Command(name = "lock", description = "Revoke every approval of a program; the next command asks again.")
    int lock() throws Exception {
        emit(call("lock", Map.of()), true);
        return 0;
    }

    @Command(name = "react", description = "React to a message. Defaults to the configured bot emoji.")
    int react(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
              @Option(names = "--id", required = true, paramLabel = "<msgid>", description = "message id — the 'id' column of messages/media") String id,
              @Option(names = "--sender", paramLabel = "<jid>", description = "override, rarely needed: normally resolved from the message row") String sender,
              @Option(names = "--from-me", description = "override, rarely needed: normally resolved from the message row") boolean fromMe,
              @Option(names = "--emoji", paramLabel = "<emoji>", description = "defaults to reactionEmoji in whatsappfx_settings.toml") String emoji) throws Exception {
        emit(call("react", args("chat", chat, "id", id, "sender", sender, "fromMe", fromMe, "emoji", emoji)), true);
        return 0;
    }

    @Command(name = "call-reject", description = "Decline an incoming call. Answering is not possible.")
    int callReject(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID of the call row") String chat,
                   @Option(names = "--id", required = true, paramLabel = "<callId>", description = "the call id, or the row id call-<callId>-offer") String id) throws Exception {
        emit(call("call.reject", args("chat", chat, "id", id)), true);
        return 0;
    }

    @Command(name = "star", description = "Star or unstar a message. Private to your own devices.")
    int star(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
             @Option(names = "--id", required = true, paramLabel = "<msgid>", description = "message id — the 'id' column of messages/media") String id,
             @Option(names = "--sender", paramLabel = "<jid>", description = "override, rarely needed: normally resolved from the message row") String sender,
             @Option(names = "--from-me", description = "override, rarely needed: normally resolved from the message row") boolean fromMe,
             @Option(names = "--off", description = "unstar instead of star") boolean off) throws Exception {
        emit(call("star", args("chat", chat, "id", id, "sender", sender, "fromMe", fromMe, "starred", !off)), true);
        return 0;
    }

    @Command(name = "reply", description = "Reply quoting a specific message, with text or a file.")
    int reply(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
              @Option(names = "--id", required = true, paramLabel = "<msgid>", description = "the message being replied to") String id,
              @Option(names = {"--text", "--caption"}, paramLabel = "<text>", description = "required unless --file is given; the caption on it when both are given") String text,
              @Option(names = "--file", paramLabel = "<path>", description = "reply with a file instead of a text-only reply") String file,
              @Option(names = "--as", paramLabel = "<kind>", description = "image, video, audio, voice, document or sticker — overrides what the extension suggests; voice and sticker are reachable only through this") String as,
              @Option(names = "--filename", paramLabel = "<name>", description = "the name the recipient sees on a document") String filename,
              @Option(names = "--sender", paramLabel = "<jid>", description = "override, rarely needed: normally resolved from the message row") String sender) throws Exception {
        if (file == null) {
            if (text == null) throw new IllegalArgumentException("--text is required unless --file is given");
            emit(call("reply", args("chat", chat, "id", id, "text", text, "sender", sender)), true);
            return 0;
        }
        var path = Path.of(file).toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IllegalArgumentException("no such file: " + path);
        emit(call("send.media", args("chat", chat, "path", path.toString(), "caption", text, "as", as,
                "filename", filename, "quoteId", id, "sender", sender)), true);
        return 0;
    }

    @Command(name = "reindex", description = "Rebuild the index from the journal. Additive; returns a job.")
    int reindex() throws Exception {
        emit(call("reindex", Map.of()), true);
        return 0;
    }

    @Command(name = "key", description = "The key that encrypts this PC's linked-device store: show its recovery code, or put a key back from one.")
    int key(@Option(names = "--recovery-code", description = "print the key as 56 characters to write down (a CONTROL approval)") boolean show,
            @Option(names = "--restore", paramLabel = "<code>", description = "put the key back from its recovery code; refuses to replace a different key unless --replace") String code,
            @Option(names = "--replace", description = "with --restore: replace the key that is here") boolean replace) throws Exception {
        if (code != null) emit(call("key.restore", args("code", code, "replace", replace)), true);
        else if (show) emit(call("key.recovery", Map.of()), true);
        else throw new IllegalArgumentException("say --recovery-code or --restore <code>");
        return 0;
    }

    @Command(name = "quit", description = "Stop the app cleanly — writes the index out and closes the connector.")
    int quit() throws Exception {
        emit(call("quit", Map.of()), true);
        return 0;
    }

    @Command(name = "autostart", description = "Start the app at logon, so the link never lapses.")
    int autostart(@Option(names = "--set", paramLabel = "<on|off|status>", defaultValue = "status", description = "a shortcut in the Startup folder; no administrator needed") String set) throws Exception {
        emit(call("autostart", Map.of("set", set)), true);
        return 0;
    }

    @Command(name = "send", description = "Send a text message, or one or more files.")
    int send(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID; find it with: chats --contains <name>") String chat,
             @Option(names = {"--text", "--caption"}, paramLabel = "<text>", description = "required unless --file is given; with --file it captions the first file only") String text,
             @Option(names = "--file", paramLabel = "<path>", description = "a file to send; repeat for more than one, each becomes its own message") List<String> files,
             @Option(names = "--as", paramLabel = "<kind>", description = "image, video, audio, voice, document or sticker — overrides what the extension suggests; voice and sticker are reachable only through this") String as,
             @Option(names = "--filename", paramLabel = "<name>", description = "the name the recipient sees on a document; only with a single --file") String filename,
             @Option(names = "--draft", description = "send nothing to that chat: put the text in YOUR OWN chat as a WhatsApp link that opens that chat with the text ready, to review and send by hand on the phone (a group gets plain text, since WhatsApp has no such link for groups); the text is also put in the chat window's write box") boolean draft) throws Exception {
        if (draft) {
            if (text == null) throw new IllegalArgumentException("--text is required with --draft");
            if (files != null && !files.isEmpty()) throw new IllegalArgumentException("--draft is for text only");
            emit(call("draft", args("chat", chat, "text", text)), true);
            return 0;
        }
        if (files == null || files.isEmpty()) {
            if (text == null) throw new IllegalArgumentException("--text is required when no --file is given");
            emit(call("send.text", args("chat", chat, "text", text)), true);
            return 0;
        }
        if (filename != null && files.size() > 1) {
            throw new IllegalArgumentException("--filename only applies to a single --file");
        }
        if (text != null && files.size() > 1) {
            Out.STDERR.println("note: --caption applies to the first file only; the rest send without one.");
        }
        for (var i = 0; i < files.size(); i++) {
            var path = Path.of(files.get(i)).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("no such file: " + path);
            var caption = i == 0 ? text : null;
            emit(call("send.media", args("chat", chat, "path", path.toString(), "caption", caption,
                    "as", as, "filename", filename)), true);
        }
        return 0;
    }

    @Command(name = "edit", description = "Replace the text of one of your own messages (WhatsApp allows about 15 minutes).")
    int edit(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID") String chat,
             @Option(names = "--id", required = true, paramLabel = "<msgid>", description = "your message") String id,
             @Option(names = "--text", required = true, paramLabel = "<text>", description = "the new text") String text) throws Exception {
        emit(call("edit", args("chat", chat, "id", id, "text", text)), true);
        return 0;
    }

    @Command(name = "delete", description = "Delete one of your own messages for everyone.")
    int delete(@Option(names = "--chat", required = true, paramLabel = "<jid>", description = "chat JID") String chat,
               @Option(names = "--id", required = true, paramLabel = "<msgid>", description = "your message") String id) throws Exception {
        emit(call("delete", args("chat", chat, "id", id)), true);
        return 0;
    }

    /** Nulls are dropped rather than sent, so an absent filter never reads as an empty one. */
    private static Map<String, Object> args(Object... kv) {
        var m = new LinkedHashMap<String, Object>();
        for (var i = 0; i < kv.length; i += 2) if (kv[i + 1] != null) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
