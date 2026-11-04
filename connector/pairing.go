package main

import (
	"context"
	"encoding/json"
	"errors"

	"go.mau.fi/whatsmeow"
)

// The QR channel must be taken before Connect, so pairing is always start-then-listen rather than
// a blocking call: the daemon gets an immediate ack and the codes arrive as events.
//
// It must also be taken while DISCONNECTED — GetQRChannel refuses outright on a connected client —
// and both the code path and a plain reconnect leave one connected. So a QR attempt following any
// other attempt failed on ErrQRAlreadyConnected, which reads like a pairing fault and is not one.
// Drop the socket first; the connect below re-establishes it, in the order the channel requires.
func (s *session) pairQR(id string) error {
	if err := s.preparePair(); err != nil {
		return err
	}
	c := s.cli()
	if c.IsConnected() {
		c.Disconnect()
	}
	ch, err := c.GetQRChannel(context.Background())
	if err != nil {
		return err
	}
	if err := s.connect(); err != nil {
		return err
	}
	go func() {
		for item := range ch {
			switch item.Event {
			case whatsmeow.QRChannelEventCode:
				emit("qr", map[string]any{"code": item.Code, "timeoutMs": item.Timeout.Milliseconds()})
			case whatsmeow.QRChannelEventError:
				emit("pair", map[string]any{"status": "error", "error": item.Error.Error()})
			default:
				// Not "pair": the channel reports its own success alongside the real PairSuccess event,
				// carrying no JID, and the daemon logged that second one as "paired as null".
				emit("qrchannel", map[string]any{"status": item.Event})
			}
		}
	}()
	ok(id, map[string]any{"started": true, "method": "qr"})
	return nil
}

// WhatsApp validates the display name against a list of real browsers and rejects anything else with
// a 400, so this string cannot carry our own product name the way the device name can.
func (s *session) pairCode(r request) error {
	var a struct {
		Phone  string `json:"phone"`
		Notify bool   `json:"notify"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	if a.Phone == "" {
		return errors.New("phone required, in full international form without + or spaces")
	}
	if err := s.preparePair(); err != nil {
		return err
	}
	if err := s.connect(); err != nil {
		return err
	}
	code, err := s.cli().PairPhone(context.Background(), a.Phone, a.Notify, whatsmeow.PairClientChrome, "Chrome (Windows)")
	if err != nil {
		return err
	}
	emit("paircode", map[string]any{"code": code})
	ok(r.ID, map[string]any{"code": code, "method": "code"})
	return nil
}
