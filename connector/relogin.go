package main

import (
	"context"
	"encoding/json"
	"time"
)

/*
Forgetting a linked device and being ready to pair again.

Whichever way the link ends — WhatsApp removed it, the phone unlinked it, or somebody asked here —
the local device is unusable afterwards and the only recovery is a fresh one. rearm is that recovery,
and it is the single path: the automatic reaction to a logout and the deliberate 'forget' verb both
go through it, so there is no second implementation to drift.
*/
func (s *session) rearm(reason string) map[string]any {
	s.resetMu.Lock()
	defer s.resetMu.Unlock()

	old := s.cli()
	prev := loadStr(s.lastJid)
	if old.Store.ID == nil && !old.Store.Deleted {
		return map[string]any{"rearmed": false, "reason": reason, "state": "already unpaired and ready to pair"}
	}
	s.rememberJid(old.Store)

	old.RemoveEventHandlers()
	old.Disconnect()
	if !old.Store.Deleted {
		if err := old.Store.Delete(context.Background()); err != nil {
			logf("forget: deleting the local device failed: %v", err)
		}
	}
	s.ref.Store(s.newClient(s.container.NewDevice()))

	out := map[string]any{"rearmed": true, "reason": reason, "previousJid": prev, "state": "ready to pair"}
	s.emitState("session.reset", out)
	return out
}

// whatsmeow dispatches LoggedOut on its own goroutine and only then deletes the device, so a rearm
// racing in immediately would be deleting a device its owner is still working on. The pause costs
// nothing here: nothing can be done with this session until somebody reaches for their phone.
func (s *session) autoRearm(reason string) {
	s.logoutReason.Store(reason)
	s.logoutAt.Store(time.Now().Unix())
	time.Sleep(time.Second)
	s.rearm("logged out: " + reason)
}

// Reached by the 'logout' command. The server call unlinks the device on the phone as well and is
// worth trying first, but it is exactly the call that fails when the account is banned or the link
// already gone — and refusing to forget locally because the server would not answer is how a session
// gets stuck. So a failure there is logged and the local half proceeds regardless.
func (s *session) forget(r request) error {
	var a struct {
		Local bool `json:"local"`
	}
	if len(r.Args) > 0 {
		_ = json.Unmarshal(r.Args, &a)
	}
	c := s.cli()
	route := "local"
	if !a.Local && c.Store.ID != nil && !c.Store.Deleted {
		if err := c.Logout(context.Background()); err != nil {
			logf("logout: the server refused (%v); forgetting the device locally", err)
		} else {
			route = "server"
		}
	}
	s.logoutReason.Store("forgotten here (" + route + ")")
	s.logoutAt.Store(time.Now().Unix())
	out := s.rearm("forget requested")
	out["route"] = route
	ok(r.ID, out)
	return nil
}

// Pairing is the one command that has to work when the device is dead, since that is the only
// situation in which anybody reaches for it. It used to be the one command that could not.
func (s *session) preparePair() error {
	c := s.cli()
	if c.Store.Deleted {
		s.rearm("pairing requested on a device WhatsApp had removed")
		return nil
	}
	if c.Store.ID != nil {
		return errorf("already paired as %s — run 'whatsappcli logout' first", c.Store.ID.String())
	}
	return nil
}
