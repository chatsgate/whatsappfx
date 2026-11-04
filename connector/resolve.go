package main

import (
	"context"
	"encoding/json"

	"go.mau.fi/whatsmeow/types"
)

// Turns a batch of JIDs into whatever the account actually knows about them: the phone number behind
// a LID, and the contact name behind that. Exists so chats already stored under a LID can be given
// names without pairing again or re-running history sync.
func (s *session) resolve(r request) error {
	var a struct {
		Jids []string `json:"jids"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	ctx := context.Background()
	out := make([]map[string]any, 0, len(a.Jids))
	for _, raw := range a.Jids {
		jid, err := types.ParseJID(raw)
		if err != nil {
			continue
		}
		row := map[string]any{"jid": raw}
		lookup := jid
		if pn := s.pn(jid); pn != "" {
			row["pn"] = pn
			if parsed, err := types.ParseJID(pn); err == nil {
				lookup = parsed
			}
		}
		if jid.Server == types.GroupServer {
			if info, err := s.cli().GetGroupInfo(ctx, jid); err == nil {
				row["name"] = info.Name
			}
		} else if c, err := s.cli().Store.Contacts.GetContact(ctx, lookup); err == nil && c.Found {
			row["name"] = firstNonEmpty(c.FullName, c.BusinessName, c.PushName, c.FirstName)
			row["pushName"] = c.PushName
		}
		out = append(out, row)
	}
	ok(r.ID, out)
	return nil
}
