package main

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"go.mau.fi/whatsmeow/appstate"
	"go.mau.fi/whatsmeow/types"
)

type request struct {
	ID   string          `json:"id"`
	Cmd  string          `json:"cmd"`
	Args json.RawMessage `json:"args"`
}

type msgRef struct {
	Chat   string `json:"chat"`
	Sender string `json:"sender"`
	ID     string `json:"id"`
	FromMe bool   `json:"fromMe"`
	Ts     int64  `json:"ts"`
}

func (m msgRef) jids() (chat, sender types.JID, err error) {
	if chat, err = types.ParseJID(m.Chat); err != nil {
		return
	}
	if m.Sender == "" {
		sender = chat
		return
	}
	sender, err = types.ParseJID(m.Sender)
	return
}

var needsLogin = map[string]bool{
	"groups": true, "contacts": true, "history.request": true, "media.download": true,
	"media.retry": true, "react": true, "star": true, "send.text": true, "send.media": true, "reply": true,
	"resolve": true, "call.reject": true, "edit": true, "delete": true,
}

func (s *session) handle(r request) {
	var err error
	if needsLogin[r.Cmd] {
		if err = s.requireLogin(); err != nil {
			fail(r.ID, err)
			return
		}
	}
	switch r.Cmd {
	case "status":
		ok(r.ID, s.status())
	case "connect":
		if err = s.connect(); err == nil {
			ok(r.ID, s.status())
		}
	case "disconnect":
		s.cli().Disconnect()
		ok(r.ID, s.status())
	case "logout":
		err = s.forget(r)
	case "pair.qr":
		err = s.pairQR(r.ID)
	case "pair.code":
		err = s.pairCode(r)
	case "groups":
		err = s.groups(r.ID)
	case "resolve":
		err = s.resolve(r)
	case "contacts":
		err = s.contacts(r.ID)
	case "history.request":
		err = s.historyRequest(r)
	case "media.download":
		err = s.mediaDownload(r)
	case "media.retry":
		err = s.mediaRetry(r)
	case "react":
		err = s.react(r)
	case "star":
		err = s.star(r)
	case "send.text":
		err = s.sendText(r)
	case "send.media":
		err = s.sendMedia(r)
	case "reply":
		err = s.reply(r)
	case "delete":
		err = s.deleteMsg(r)
	case "edit":
		err = s.edit(r)
	case "call.reject":
		err = s.callReject(r)
	case "shutdown":
		ok(r.ID, map[string]any{"bye": true})
		s.shutdown()
	default:
		err = errors.New("unknown command: " + r.Cmd)
	}
	if err != nil {
		fail(r.ID, err)
	}
}

// Backoff between dial attempts. whatsmeow retries a connection it has already made and lost, and
// nothing retries the FIRST dial, so one unlucky DNS lookup defeats the command outright. Measured
// on this machine: an attempt failed with "lookup web.whatsapp.com: no such host" and the same call
// twenty seconds later succeeded, on a link where PowerShell resolved the name throughout. It cost
// two failed re-pairs at the one moment re-pairing mattered.
var dialBackoff = []time.Duration{3 * time.Second, 5 * time.Second, 7 * time.Second, 9 * time.Second}

// Connecting unpaired is legitimate — that is how pairing itself begins — so the only state refused
// here is a device WhatsApp has removed, which whatsmeow answers with a phrase nobody can act on.
func (s *session) connect() error {
	c := s.cli()
	if c.Store.Deleted {
		return errorf("the linked device was removed by WhatsApp; pair this machine again")
	}
	if c.IsConnected() {
		return nil
	}
	err := c.Connect()
	for i, wait := range dialBackoff {
		if err == nil || c.IsConnected() {
			return nil
		}
		// Logged rather than swallowed: a link that needs four attempts every time is worth seeing.
		logf("connect attempt %d failed, retrying in %v: %v", i+1, wait, err)
		time.Sleep(wait)
		err = c.Connect()
	}
	return err
}

func (s *session) groups(id string) error {
	gs, err := s.cli().GetJoinedGroups(context.Background())
	if err != nil {
		return err
	}
	out := make([]map[string]any, 0, len(gs))
	for _, g := range gs {
		out = append(out, map[string]any{
			"jid": g.JID.String(), "name": g.Name, "topic": g.Topic,
			"created": g.GroupCreated.Unix(), "participants": len(g.Participants),
			"isAnnounce": g.IsAnnounce, "owner": g.OwnerJID.String(),
		})
	}
	ok(id, out)
	return nil
}

func (s *session) contacts(id string) error {
	all, err := s.cli().Store.Contacts.GetAllContacts(context.Background())
	if err != nil {
		return err
	}
	out := make([]map[string]any, 0, len(all))
	for jid, c := range all {
		out = append(out, map[string]any{
			"jid": jid.String(), "fullName": c.FullName, "firstName": c.FirstName,
			"pushName": c.PushName, "businessName": c.BusinessName,
		})
	}
	ok(id, out)
	return nil
}

func (s *session) historyRequest(r request) error {
	var a struct {
		msgRef
		Count int `json:"count"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	chat, sender, err := a.jids()
	if err != nil {
		return err
	}
	if a.Count <= 0 {
		a.Count = 50
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: chat, Sender: sender, IsFromMe: a.FromMe},
		ID:            a.ID,
		Timestamp:     time.Unix(a.Ts, 0),
	}
	resp, err := s.cli().SendPeerMessage(context.Background(), s.cli().BuildHistorySyncRequest(info, a.Count))
	if err != nil {
		return err
	}
	ok(r.ID, map[string]any{"requestId": resp.ID, "count": a.Count})
	return nil
}

func (s *session) react(r request) error {
	var a struct {
		msgRef
		Emoji string `json:"emoji"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	chat, sender, err := a.jids()
	if err != nil {
		return err
	}
	resp, err := s.cli().SendMessage(context.Background(), chat, s.cli().BuildReaction(chat, sender, a.ID, a.Emoji))
	if err != nil {
		return err
	}
	ok(r.ID, map[string]any{"id": resp.ID, "ts": resp.Timestamp.Unix()})
	return nil
}

func (s *session) star(r request) error {
	var a struct {
		msgRef
		Starred bool `json:"starred"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	chat, sender, err := a.jids()
	if err != nil {
		return err
	}
	patch := appstate.BuildStar(chat, sender, a.ID, a.FromMe, a.Starred)
	if err := s.cli().SendAppState(context.Background(), patch); err != nil {
		return err
	}
	ok(r.ID, map[string]any{"starred": a.Starred})
	return nil
}

// A device row that has never been paired has nil sub-stores, so every verb that touches account
// state has to say so rather than dereference its way into a panic.
func (s *session) requireLogin() error {
	if s.cli().Store.ID == nil {
		return errors.New("not paired: run pair first")
	}
	return nil
}
