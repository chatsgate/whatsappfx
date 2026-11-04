package main

import (
	"time"

	"go.mau.fi/whatsmeow/proto/waHistorySync"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

func (s *session) onEvent(raw any) {
	switch e := raw.(type) {
	case *events.Message:
		emit("message", s.fromEvent("live", e))
	case *events.HistorySync:
		s.onHistory(e)
	case *events.Connected:
		s.rememberJid(s.cli().Store)
		emit("connected", s.status())
	case *events.PairSuccess:
		s.logoutReason.Store("")
		s.banReason.Store("")
		s.rememberJid(s.cli().Store)
		// Full state, so the daemon is not left reporting "not paired" in the gap between pairing
		// succeeding and the connection coming up behind it.
		s.emitState("pair", map[string]any{"status": "success", "jid": e.ID.String(), "platform": e.Platform})
	case *events.PairError:
		emit("pair", map[string]any{"status": "error", "error": e.Error.Error()})
	case *events.Disconnected:
		s.emitState("disconnected", nil)
	case *events.StreamReplaced:
		s.emitState("disconnected", map[string]any{"reason": "stream-replaced"})
	case *events.LoggedOut:
		// whatsmeow has already deleted the device by now, so there is nothing left here to protect
		// and every command would fail until a fresh one exists. Build it without being asked.
		s.emitState("loggedout", map[string]any{"reason": e.Reason.String(), "code": int(e.Reason),
			"onConnect": e.OnConnect, "loggedIn": false, "connected": false, "needsPairing": true})
		go s.autoRearm(e.Reason.String())
	case *events.ConnectFailure:
		emit("connectfailure", map[string]any{"reason": e.Reason.String(), "message": e.Message})
	case *events.TemporaryBan:
		s.banReason.Store(e.Code.String())
		s.banUntil.Store(time.Now().Add(e.Expire).Unix())
		s.emitState("banned", map[string]any{"code": e.Code.String(), "expiresInSec": int64(e.Expire.Seconds())})
	case *events.ClientOutdated:
		emit("outdated", nil)
	case *events.OfflineSyncCompleted:
		emit("offlinesync", map[string]any{"count": e.Count})
	case *events.PushName:
		emit("contact", map[string]any{"jid": e.JID.String(), "pushName": e.NewPushName})
	case *events.Contact:
		emit("contact", map[string]any{"jid": e.JID.String(), "fullName": e.Action.GetFullName()})
	case *events.MediaRetry:
		s.onMediaRetry(e)
	case *events.AppStateSyncComplete:
		emit("appstate", map[string]any{"name": string(e.Name)})
	default:
		s.onCall(raw)
	}
}

func (s *session) onHistory(e *events.HistorySync) {
	d := e.Data
	emit("historymeta", map[string]any{
		"syncType":      d.GetSyncType().String(),
		"progress":      d.GetProgress(),
		"chunkOrder":    d.GetChunkOrder(),
		"conversations": len(d.GetConversations()),
	})
	for _, pn := range d.GetPushnames() {
		emit("contact", map[string]any{"jid": pn.GetID(), "pushName": pn.GetPushname()})
	}
	for _, conv := range d.GetConversations() {
		chat, err := types.ParseJID(conv.GetID())
		if err != nil {
			logf("history: unparseable chat id %q: %v", conv.GetID(), err)
			continue
		}
		emit("chat", s.chatPayload(chat, conv))
		for _, hm := range conv.GetMessages() {
			parsed, err := s.cli().ParseWebMessage(chat, hm.GetMessage())
			if err != nil {
				continue
			}
			emit("message", s.fromEvent("history", parsed))
		}
	}
}

func (s *session) chatPayload(chat types.JID, c *waHistorySync.Conversation) map[string]any {
	return map[string]any{
		"jid":             chat.String(),
		"name":            c.GetName(),
		"mediaVisibility": c.GetMediaVisibility().String(),
		"archived":        c.GetArchived(),
		"pinned":          c.GetPinned(),
		"muteEndTime":     c.GetMuteEndTime(),
		"unreadCount":     c.GetUnreadCount(),
		"lastMsgTs":       c.GetLastMsgTimestamp(),
		"readOnly":        c.GetReadOnly(),
		"endOfHistory":    c.GetEndOfHistoryTransfer(),
		"messages":        len(c.GetMessages()),
		"pn":              s.pn(chat),
	}
}
