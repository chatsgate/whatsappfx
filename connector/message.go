package main

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
	"google.golang.org/protobuf/encoding/protojson"
)

var marshaller = protojson.MarshalOptions{EmitUnpopulated: false, UseProtoNames: true}

func protoJSON(m *waE2E.Message) json.RawMessage {
	if m == nil {
		return nil
	}
	b, err := marshaller.Marshal(m)
	if err != nil {
		logf("protojson failed: %v", err)
		return nil
	}
	return json.RawMessage(b)
}

func textOf(m *waE2E.Message) string {
	switch {
	case m == nil:
		return ""
	case m.Conversation != nil:
		return m.GetConversation()
	case m.ExtendedTextMessage != nil:
		return m.GetExtendedTextMessage().GetText()
	case m.ImageMessage != nil:
		return m.GetImageMessage().GetCaption()
	case m.VideoMessage != nil:
		return m.GetVideoMessage().GetCaption()
	case m.DocumentMessage != nil:
		return firstNonEmpty(m.GetDocumentMessage().GetCaption(), m.GetDocumentMessage().GetTitle())
	case m.ReactionMessage != nil:
		return m.GetReactionMessage().GetText()
	case m.PollCreationMessage != nil:
		return m.GetPollCreationMessage().GetName()
	case m.ButtonsResponseMessage != nil:
		return m.GetButtonsResponseMessage().GetSelectedDisplayText()
	case m.ListResponseMessage != nil:
		return m.GetListResponseMessage().GetTitle()
	}
	return ""
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if v != "" {
			return v
		}
	}
	return ""
}

func pickDownloadable(m *waE2E.Message) (whatsmeow.DownloadableMessage, string) {
	switch {
	case m == nil:
		return nil, ""
	case m.ImageMessage != nil:
		return m.ImageMessage, "image"
	case m.VideoMessage != nil:
		return m.VideoMessage, "video"
	case m.AudioMessage != nil:
		return m.AudioMessage, "audio"
	case m.DocumentMessage != nil:
		return m.DocumentMessage, "document"
	case m.StickerMessage != nil:
		return m.StickerMessage, "sticker"
	}
	return nil, ""
}

// The flat summary the Java side needs to apply attachment policy and choose a filename without
// walking the protobuf itself.
func mediaOf(m *waE2E.Message) map[string]any {
	d, kind := pickDownloadable(m)
	if d == nil {
		return nil
	}
	out := map[string]any{"kind": kind}
	switch v := d.(type) {
	case *waE2E.ImageMessage:
		fill(out, v.GetMimetype(), "", v.GetFileLength(), v.GetFileSHA256(), v.GetDirectPath())
	case *waE2E.VideoMessage:
		fill(out, v.GetMimetype(), "", v.GetFileLength(), v.GetFileSHA256(), v.GetDirectPath())
	case *waE2E.AudioMessage:
		fill(out, v.GetMimetype(), "", v.GetFileLength(), v.GetFileSHA256(), v.GetDirectPath())
	case *waE2E.DocumentMessage:
		fill(out, v.GetMimetype(), v.GetFileName(), v.GetFileLength(), v.GetFileSHA256(), v.GetDirectPath())
	case *waE2E.StickerMessage:
		fill(out, v.GetMimetype(), "", v.GetFileLength(), v.GetFileSHA256(), v.GetDirectPath())
	}
	return out
}

func fill(out map[string]any, mime, name string, size uint64, sha []byte, directPath string) {
	out["mimetype"] = mime
	if name != "" {
		out["filename"] = name
	}
	out["size"] = size
	out["sha256"] = base64.StdEncoding.EncodeToString(sha)
	out["expired"] = directPath == ""
}

// History sync addresses chats and senders by LID while the contact list is keyed by phone number,
// so without this every name lookup misses. whatsmeow already persists the mapping it receives
// during history sync; this only reads it back.
func (s *session) pn(jid types.JID) string {
	if jid.IsEmpty() || jid.Server != types.HiddenUserServer {
		return ""
	}
	found, err := s.cli().Store.LIDs.GetPNForLID(context.Background(), jid)
	if err != nil || found.IsEmpty() {
		return ""
	}
	return found.String()
}

func messagePayload(source string, info types.MessageInfo, msg *waE2E.Message) map[string]any {
	p := map[string]any{
		"source":   source,
		"chat":     info.Chat.String(),
		"sender":   info.Sender.String(),
		"fromMe":   info.IsFromMe,
		"isGroup":  info.IsGroup,
		"id":       info.ID,
		"ts":       info.Timestamp.Unix(),
		"pushName": info.PushName,
		"type":     info.Type,
		"text":     textOf(msg),
		"body":     protoJSON(msg),
	}
	if info.MediaType != "" {
		p["mediaType"] = info.MediaType
	}
	if !info.SenderAlt.IsEmpty() {
		p["senderAlt"] = info.SenderAlt.String()
	}
	if m := mediaOf(msg); m != nil {
		p["media"] = m
	}
	return p
}

func (s *session) fromEvent(source string, e *events.Message) map[string]any {
	p := messagePayload(source, e.Info, e.Message)
	if v := s.pn(e.Info.Chat); v != "" {
		p["chatPn"] = v
	}
	if v := s.pn(e.Info.Sender); v != "" {
		p["senderPn"] = v
	}
	if e.IsViewOnce || e.IsViewOnceV2 || e.IsViewOnceV2Extension {
		p["viewOnce"] = true
	}
	if e.IsEdit {
		p["edit"] = true
	}
	return p
}

// Report a message this device just sent, as the same "message" event an incoming one produces.
//
// WhatsApp delivers your messages to your OTHER linked devices, never back to the one that sent
// them, so without this our own sends and replies leave no trace in the journal or the index. That
// matters for the whole point of the write phase: a reply saying where a bill landed is a record, and
// a record that exists only in the phone's UI is not one this tool can read back.
func (s *session) emitSent(chat types.JID, id string, ts time.Time, msg *waE2E.Message) {
	s.emitSentAs(chat, id, ts, msg, "text", "")
}

// emitSentAs is emitSent widened for a media send: msgType records "media" rather than "text", and
// sentFrom — the local path the file was uploaded from — rides along in the media block so the Java
// side can file the same bytes onto the shared drive rather than waiting for a backfill to download
// them back from WhatsApp.
func (s *session) emitSentAs(chat types.JID, id string, ts time.Time, msg *waE2E.Message, msgType, sentFrom string) {
	own := s.cli().Store.ID
	if own == nil {
		return
	}
	info := types.MessageInfo{
		MessageSource: types.MessageSource{
			Chat:     chat,
			Sender:   *own,
			IsFromMe: true,
			IsGroup:  chat.Server == types.GroupServer,
		},
		ID:        id,
		Type:      msgType,
		PushName:  s.cli().Store.PushName,
		Timestamp: ts,
	}
	p := messagePayload("sent", info, msg)
	if v := s.pn(chat); v != "" {
		p["chatPn"] = v
	}
	if sentFrom != "" {
		if m, isMap := p["media"].(map[string]any); isMap {
			m["sentFrom"] = sentFrom
		}
	}
	emit("message", p)
}
