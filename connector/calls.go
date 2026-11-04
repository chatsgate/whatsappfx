package main

import (
	"context"
	"encoding/json"

	waBinary "go.mau.fi/whatsmeow/binary"
	"go.mau.fi/whatsmeow/types"
	"go.mau.fi/whatsmeow/types/events"
)

// Call signalling as a "call" event shaped like a message, so the Java side journals and indexes it
// on the message path. One row per state: the id carries the state, so an offer and the terminate
// that follows it are both kept rather than the second being dropped as a duplicate of the first.
// whatsmeow carries signalling only; there is no media stack, so nothing here can answer a call.
func (s *session) onCall(raw any) {
	switch e := raw.(type) {
	case *events.CallOffer:
		s.emitCall("offer", e.BasicCallMeta, mediaOfOffer(e.Data), "", e.RemotePlatform)
	case *events.CallOfferNotice:
		s.emitCall("offer", e.BasicCallMeta, e.Media, e.Type, "")
	case *events.CallAccept:
		s.emitCall("accept", e.BasicCallMeta, "", "", e.RemotePlatform)
	case *events.CallTerminate:
		s.emitCall("terminate", e.BasicCallMeta, "", e.Reason, "")
	case *events.CallReject:
		s.emitCall("reject", e.BasicCallMeta, "", "", "")
	}
}

func mediaOfOffer(n *waBinary.Node) string {
	if n != nil {
		if _, isVideo := n.GetOptionalChildByTag("video"); isVideo {
			return "video"
		}
	}
	return "audio"
}

func (s *session) emitCall(state string, m types.BasicCallMeta, media, detail, platform string) {
	creator := m.CallCreator.ToNonAD()
	chat := creator
	isGroup := !m.GroupJID.IsEmpty()
	if isGroup {
		chat = m.GroupJID
	}
	own := s.cli().Store.ID
	fromMe := own != nil && creator.User == own.User
	call := map[string]any{"callId": m.CallID, "state": state, "from": m.From.String()}
	if media != "" {
		call["media"] = media
	}
	if detail != "" {
		call["detail"] = detail
	}
	if platform != "" {
		call["platform"] = platform
	}
	if !m.CallCreatorAlt.IsEmpty() {
		call["creatorAlt"] = m.CallCreatorAlt.ToNonAD().String()
	}
	p := map[string]any{
		"source": "live", "chat": chat.String(), "sender": creator.String(), "fromMe": fromMe,
		"isGroup": isGroup, "id": "call-" + m.CallID + "-" + state, "ts": m.Timestamp.Unix(),
		"type": "call", "text": callText(state, media, detail), "call": call,
	}
	if v := firstNonEmpty(s.pn(chat), altPn(m.CallCreatorAlt)); v != "" && !isGroup {
		p["chatPn"] = v
	}
	if v := firstNonEmpty(s.pn(creator), altPn(m.CallCreatorAlt)); v != "" {
		p["senderPn"] = v
	}
	emit("call", p)
}

func altPn(j types.JID) string {
	if j.IsEmpty() || j.Server != types.DefaultUserServer {
		return ""
	}
	return j.ToNonAD().String()
}

func callText(state, media, detail string) string {
	t := "call " + state
	if media != "" {
		t = media + " " + t
	}
	if detail != "" {
		t += " (" + detail + ")"
	}
	return t
}

// Declines an incoming call. The caller sees it as declined, as if the phone's red button had been
// pressed; the phone itself may still ring until WhatsApp tells it the call ended.
func (s *session) callReject(r request) error {
	var a struct {
		From   string `json:"from"`
		CallID string `json:"callId"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	from, err := types.ParseJID(a.From)
	if err != nil {
		return err
	}
	if a.CallID == "" {
		return errorf("callId is required")
	}
	if err := s.cli().RejectCall(context.Background(), from, a.CallID); err != nil {
		return err
	}
	ok(r.ID, map[string]any{"rejected": a.CallID, "from": from.ToNonAD().String()})
	return nil
}
