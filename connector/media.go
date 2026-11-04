package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"os"
	"path/filepath"
	"time"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"
)

type downloadArgs struct {
	msgRef
	Body json.RawMessage `json:"body"`
	Out  string          `json:"out"`
}

// Bytes never cross the pipe: the connector writes the file and reports where it put it, which also
// keeps the one expensive operation off the single stdout lock.
func (s *session) mediaDownload(r request) error {
	var a downloadArgs
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	var msg waE2E.Message
	if err := protojson.Unmarshal(a.Body, &msg); err != nil {
		return err
	}
	d, kind := pickDownloadable(&msg)
	if d == nil {
		return whatsmeow.ErrNothingDownloadableFound
	}
	out := a.Out
	if out == "" {
		out = filepath.Join(s.spoolDir, a.ID+extFor(kind))
	}
	if err := os.MkdirAll(filepath.Dir(out), 0o755); err != nil {
		return err
	}
	tmp := out + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	err = s.cli().DownloadToFile(context.Background(), d, f)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return classify(err)
	}
	if err := os.Rename(tmp, out); err != nil {
		return err
	}
	sum, size, err := digest(out)
	if err != nil {
		return err
	}
	ok(r.ID, map[string]any{"path": out, "size": size, "sha256": sum, "kind": kind})
	return nil
}

// A 404 or 410 means the server dropped it, which is a different problem from a broken download and
// the only one a media-retry receipt can fix.
func classify(err error) error {
	if errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith404) || errors.Is(err, whatsmeow.ErrMediaDownloadFailedWith410) {
		return errors.New("expired: media no longer on WhatsApp servers")
	}
	return err
}

func extFor(kind string) string {
	switch kind {
	case "image":
		return ".jpg"
	case "video":
		return ".mp4"
	case "audio":
		return ".ogg"
	case "sticker":
		return ".webp"
	}
	return ".bin"
}

func digest(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.Copy(h, f)
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// Asks the phone to re-upload a file WhatsApp's servers have dropped. It only works while the phone
// still holds the file, and the answer arrives asynchronously as a MediaRetry event.
func (s *session) mediaRetry(r request) error {
	var a downloadArgs
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	var msg waE2E.Message
	if err := protojson.Unmarshal(a.Body, &msg); err != nil {
		return err
	}
	d, kind := pickDownloadable(&msg)
	if d == nil {
		return whatsmeow.ErrNothingDownloadableFound
	}
	key := d.GetMediaKey()
	out := a.Out
	if out == "" {
		out = filepath.Join(s.spoolDir, a.ID+extFor(kind))
	}
	chat, sender, err := a.jids()
	if err != nil {
		return err
	}
	info := &types.MessageInfo{
		MessageSource: types.MessageSource{Chat: chat, Sender: sender, IsFromMe: a.FromMe},
		ID:            a.ID,
		Timestamp:     time.Unix(a.Ts, 0),
	}
	if err := s.cli().SendMediaRetryReceipt(context.Background(), info, key); err != nil {
		return err
	}
	rememberRetry(a.ID, pendingRetry{
		mediaKey: key, encHash: d.GetFileEncSHA256(), fileHash: d.GetFileSHA256(),
		mediaType: whatsmeow.GetMediaType(d), out: out, kind: kind,
	})
	ok(r.ID, map[string]any{"requested": true, "out": out})
	return nil
}

func (s *session) sendText(r request) error {
	var a struct {
		Chat string `json:"chat"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	chat, err := types.ParseJID(a.Chat)
	if err != nil {
		return err
	}
	resp, err := s.cli().SendMessage(context.Background(), chat, &waE2E.Message{Conversation: proto.String(a.Text)})
	if err != nil {
		return err
	}
	s.emitSent(chat, resp.ID, resp.Timestamp, &waE2E.Message{Conversation: proto.String(a.Text)})
	ok(r.ID, map[string]any{"id": resp.ID, "ts": resp.Timestamp.Unix()})
	return nil
}

// quoteContext builds the ContextInfo that makes a reply or a media message attach to a specific
// message rather than post as a loose one: the stanza id and the participant are what the phone uses
// to draw the arrow, and QuotedMessage is what draws the quoted block itself.
//
// The quoted body comes back over the pipe as the same protojson we emitted when the message
// arrived, so a reply to an invoice quotes the invoice rather than a paraphrase of it. Shared by
// reply and sendMedia rather than duplicated. A blank quoteID means no quote at all, which is the
// normal case for sendMedia and never happens for reply — reply refuses one itself.
func (s *session) quoteContext(quoteID, quoteSender string, quoteBody json.RawMessage) (*waE2E.ContextInfo, error) {
	if quoteID == "" {
		return nil, nil
	}
	ctxInfo := &waE2E.ContextInfo{StanzaID: proto.String(quoteID)}
	if quoteSender != "" {
		sender, err := types.ParseJID(quoteSender)
		if err != nil {
			return nil, err
		}
		ctxInfo.Participant = proto.String(sender.ToNonAD().String())
	}
	if len(quoteBody) > 0 {
		var quoted waE2E.Message
		if err := protojson.Unmarshal(quoteBody, &quoted); err != nil {
			// A quote that will not parse is not worth failing the send over; the message still
			// lands, it simply renders without the quoted block.
			logf("quote: quoted body unusable, sending without it: %v", err)
		} else {
			ctxInfo.QuotedMessage = &quoted
		}
	}
	return ctxInfo, nil
}

// A quoted reply.
func (s *session) reply(r request) error {
	var a struct {
		Chat        string          `json:"chat"`
		Text        string          `json:"text"`
		QuoteID     string          `json:"quoteId"`
		QuoteSender string          `json:"quoteSender"`
		QuoteBody   json.RawMessage `json:"quoteBody"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	if a.QuoteID == "" {
		return errorf("quoteId is required; use send.text for an unquoted message")
	}
	chat, err := types.ParseJID(a.Chat)
	if err != nil {
		return err
	}
	ctxInfo, err := s.quoteContext(a.QuoteID, a.QuoteSender, a.QuoteBody)
	if err != nil {
		return err
	}
	msg := &waE2E.Message{ExtendedTextMessage: &waE2E.ExtendedTextMessage{
		Text:        proto.String(a.Text),
		ContextInfo: ctxInfo,
	}}
	resp, err := s.cli().SendMessage(context.Background(), chat, msg)
	if err != nil {
		return err
	}
	s.emitSent(chat, resp.ID, resp.Timestamp, msg)
	ok(r.ID, map[string]any{"id": resp.ID, "ts": resp.Timestamp.Unix(), "quoted": a.QuoteID})
	return nil
}

// edit replaces the text of a message this account sent (WhatsApp allows it for about 15 minutes). The
// edited text is reported as a "message" event under the original id, so the index row is rewritten.
func (s *session) edit(r request) error {
	var a struct {
		Chat string `json:"chat"`
		ID   string `json:"id"`
		Text string `json:"text"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	if a.ID == "" || a.Text == "" {
		return errorf("id and text are required")
	}
	chat, err := types.ParseJID(a.Chat)
	if err != nil {
		return err
	}
	content := &waE2E.Message{Conversation: proto.String(a.Text)}
	resp, err := s.cli().SendMessage(context.Background(), chat, s.cli().BuildEdit(chat, a.ID, content))
	if err != nil {
		return err
	}
	s.emitSentAs(chat, a.ID, resp.Timestamp, content, "text", "")
	ok(r.ID, map[string]any{"id": a.ID, "edited": true, "ts": resp.Timestamp.Unix()})
	return nil
}

// deleteMsg revokes (deletes for everyone) a message this account sent. Only our own messages: the sender
// is left empty, which is whatsmeow's way of saying "mine". The index row is not rewritten here; the
// message stays in the local journal, which is a record and not a view of the phone.
func (s *session) deleteMsg(r request) error {
	var a struct {
		Chat string `json:"chat"`
		ID   string `json:"id"`
	}
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	if a.ID == "" {
		return errorf("id is required")
	}
	chat, err := types.ParseJID(a.Chat)
	if err != nil {
		return err
	}
	resp, err := s.cli().SendMessage(context.Background(), chat, s.cli().BuildRevoke(chat, types.EmptyJID, a.ID))
	if err != nil {
		return err
	}
	ok(r.ID, map[string]any{"id": a.ID, "deleted": true, "ts": resp.Timestamp.Unix()})
	return nil
}
