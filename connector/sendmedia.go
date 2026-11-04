package main

import (
	"bytes"
	"context"
	"encoding/json"
	"image"
	"image/color"
	_ "image/gif" // registers gif with image.Decode
	"image/jpeg"
	_ "image/png" // registers png with image.Decode
	"mime"
	"net/http"
	"os"
	"path/filepath"
	"strings"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waE2E"
	"go.mau.fi/whatsmeow/types"
	"google.golang.org/protobuf/proto"
)

// An explicit table first, ahead of the standard library's own lookup: mime.TypeByExtension reads
// the Windows registry, where a .docx can come back as something other than its real OOXML type
// depending on what else is installed, and http.DetectContentType sees an OOXML file's outer zip
// and calls the whole thing application/zip.
var extMime = map[string]string{
	".jpg": "image/jpeg", ".jpeg": "image/jpeg", ".png": "image/png", ".gif": "image/gif", ".webp": "image/webp",
	".mp4": "video/mp4", ".mov": "video/quicktime", ".webm": "video/webm",
	".ogg": "audio/ogg", ".opus": "audio/ogg", ".mp3": "audio/mpeg", ".m4a": "audio/mp4", ".wav": "audio/wav",
	".pdf": "application/pdf",
	".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
	".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
	".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
	".doc": "application/msword", ".xls": "application/vnd.ms-excel", ".ppt": "application/vnd.ms-powerpoint",
	".txt": "text/plain", ".csv": "text/csv", ".zip": "application/zip",
	".html": "text/html", ".xhtml": "text/html", ".json": "application/json", ".md": "text/markdown",
}

func detectMime(path, override string) (string, error) {
	if override != "" {
		return override, nil
	}
	ext := strings.ToLower(filepath.Ext(path))
	if m, ok := extMime[ext]; ok {
		return m, nil
	}
	if m := mime.TypeByExtension(ext); m != "" {
		return strings.SplitN(m, ";", 2)[0], nil
	}
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	buf := make([]byte, 512)
	n, _ := f.Read(buf)
	return http.DetectContentType(buf[:n]), nil
}

func kindOfMime(m string) string {
	switch {
	case strings.HasPrefix(m, "image/"):
		return "image"
	case strings.HasPrefix(m, "video/"):
		return "video"
	case strings.HasPrefix(m, "audio/"):
		return "audio"
	default:
		return "document"
	}
}

func mediaTypeFor(kind string) whatsmeow.MediaType {
	switch kind {
	case "video":
		return whatsmeow.MediaVideo
	case "audio", "voice":
		return whatsmeow.MediaAudio
	case "document":
		return whatsmeow.MediaDocument
	default: // image, sticker — a sticker uploads under the same key as an image.
		return whatsmeow.MediaImage
	}
}

// WhatsApp's own ceiling for an inline image, video or audio message. Sending anything larger under
// those types does not fail cleanly — it is the recipient who cannot open it — so this is worth
// refusing here rather than reporting as success.
const maxInlineMediaBytes = 16 * 1024 * 1024

const thumbnailBox = 100

type sendMediaArgs struct {
	Chat        string          `json:"chat"`
	Path        string          `json:"path"`
	Caption     string          `json:"caption"`
	As          string          `json:"as"`
	Filename    string          `json:"filename"`
	Mimetype    string          `json:"mimetype"`
	QuoteID     string          `json:"quoteId"`
	QuoteSender string          `json:"quoteSender"`
	QuoteBody   json.RawMessage `json:"quoteBody"`
}

func (s *session) sendMedia(r request) error {
	var a sendMediaArgs
	if err := json.Unmarshal(r.Args, &a); err != nil {
		return err
	}
	chat, err := types.ParseJID(a.Chat)
	if err != nil {
		return err
	}
	info, err := os.Stat(a.Path)
	if err != nil {
		return err
	}
	mimetype, err := detectMime(a.Path, a.Mimetype)
	if err != nil {
		return err
	}
	kind := kindOfMime(mimetype)
	if a.As != "" {
		switch a.As {
		case "image", "video", "audio", "voice", "document", "sticker":
			kind = a.As
		default:
			return errorf("unknown --as value %q: use image, video, audio, voice, document or sticker", a.As)
		}
	}
	if kind != "document" && info.Size() > maxInlineMediaBytes {
		return errorf("%s is %.1f MB, over WhatsApp's 16 MB limit for %s — send it with --as document instead",
			filepath.Base(a.Path), float64(info.Size())/(1024*1024), kind)
	}
	if a.Caption != "" && (kind == "audio" || kind == "voice" || kind == "sticker") {
		return errorf("a %s message cannot carry a caption", kind)
	}

	ctxInfo, err := s.quoteContext(a.QuoteID, a.QuoteSender, a.QuoteBody)
	if err != nil {
		return err
	}

	var jpegThumb []byte
	var width, height uint32
	if kind == "image" {
		jpegThumb, width, height = buildThumbnail(a.Path)
	}

	f, err := os.Open(a.Path)
	if err != nil {
		return err
	}
	defer f.Close()
	up, err := s.cli().UploadReader(context.Background(), f, nil, mediaTypeFor(kind))
	if err != nil {
		return err
	}

	filename := a.Filename
	if filename == "" {
		filename = filepath.Base(a.Path)
	}
	msg := buildMediaMessage(kind, mimetype, filename, a.Caption, up, ctxInfo, jpegThumb, width, height)

	resp, err := s.cli().SendMessage(context.Background(), chat, msg)
	if err != nil {
		return err
	}
	s.emitSentAs(chat, resp.ID, resp.Timestamp, msg, "media", a.Path)
	out := map[string]any{"id": resp.ID, "ts": resp.Timestamp.Unix(), "kind": kind, "mimetype": mimetype}
	if a.QuoteID != "" {
		out["quoted"] = a.QuoteID
	}
	ok(r.ID, out)
	return nil
}

func buildMediaMessage(kind, mimetype, filename, caption string, up whatsmeow.UploadResponse,
	ctxInfo *waE2E.ContextInfo, jpegThumb []byte, width, height uint32) *waE2E.Message {
	switch kind {
	case "image":
		im := &waE2E.ImageMessage{
			Mimetype: proto.String(mimetype), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), ContextInfo: ctxInfo,
		}
		if caption != "" {
			im.Caption = proto.String(caption)
		}
		if len(jpegThumb) > 0 {
			im.JPEGThumbnail = jpegThumb
		}
		if width > 0 {
			im.Width = proto.Uint32(width)
		}
		if height > 0 {
			im.Height = proto.Uint32(height)
		}
		return &waE2E.Message{ImageMessage: im}
	case "video":
		vm := &waE2E.VideoMessage{
			Mimetype: proto.String(mimetype), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), ContextInfo: ctxInfo,
		}
		if caption != "" {
			vm.Caption = proto.String(caption)
		}
		return &waE2E.Message{VideoMessage: vm}
	case "audio", "voice":
		am := &waE2E.AudioMessage{
			Mimetype: proto.String(mimetype), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), ContextInfo: ctxInfo,
			PTT: proto.Bool(kind == "voice"),
		}
		return &waE2E.Message{AudioMessage: am}
	case "sticker":
		sm := &waE2E.StickerMessage{
			Mimetype: proto.String(mimetype), URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), ContextInfo: ctxInfo,
		}
		return &waE2E.Message{StickerMessage: sm}
	default: // document
		dm := &waE2E.DocumentMessage{
			Mimetype: proto.String(mimetype), FileName: proto.String(filename), Title: proto.String(filename),
			URL: &up.URL, DirectPath: &up.DirectPath,
			MediaKey: up.MediaKey, FileEncSHA256: up.FileEncSHA256, FileSHA256: up.FileSHA256,
			FileLength: proto.Uint64(up.FileLength), ContextInfo: ctxInfo,
		}
		if caption != "" {
			dm.Caption = proto.String(caption)
		}
		return &waE2E.Message{DocumentMessage: dm}
	}
}

// buildThumbnail decodes an image with the standard library only — jpeg, png and gif, never webp,
// which the standard library cannot decode — and produces the JPEGThumbnail and dimensions that let
// a WhatsApp client lay out the bubble and draw a preview before the real file has downloaded.
// Nothing here can fail the send: an unreadable or undecodable image (webp included) logs and comes
// back empty, and the message goes out without a thumbnail rather than not going out at all.
func buildThumbnail(path string) ([]byte, uint32, uint32) {
	f, err := os.Open(path)
	if err != nil {
		logf("thumbnail: %v", err)
		return nil, 0, 0
	}
	defer f.Close()
	img, _, err := image.Decode(f)
	if err != nil {
		logf("thumbnail: decode failed, sending without one: %v", err)
		return nil, 0, 0
	}
	b := img.Bounds()
	w, h := uint32(b.Dx()), uint32(b.Dy())
	var buf bytes.Buffer
	if err := jpeg.Encode(&buf, downscale(img, thumbnailBox), &jpeg.Options{Quality: 60}); err != nil {
		logf("thumbnail: encode failed, sending without one: %v", err)
		return nil, w, h
	}
	return buf.Bytes(), w, h
}

// downscale box-averages the source down to roughly maxSide pixels on the long side.
// golang.org/x/image/draw is not in this module's cache and pulling it in needs the network, so this
// is the hand-rolled equivalent for the one thing needed here: a chat-bubble placeholder, not a
// faithful resize.
func downscale(src image.Image, maxSide int) image.Image {
	b := src.Bounds()
	sw, sh := b.Dx(), b.Dy()
	if sw <= 0 || sh <= 0 {
		return src
	}
	scale := float64(maxSide) / float64(max(sw, sh))
	if scale >= 1 {
		return src
	}
	dw, dh := int(float64(sw)*scale), int(float64(sh)*scale)
	if dw < 1 {
		dw = 1
	}
	if dh < 1 {
		dh = 1
	}
	dst := image.NewRGBA(image.Rect(0, 0, dw, dh))
	for y := 0; y < dh; y++ {
		sy0, sy1 := b.Min.Y+y*sh/dh, b.Min.Y+(y+1)*sh/dh
		if sy1 <= sy0 {
			sy1 = sy0 + 1
		}
		for x := 0; x < dw; x++ {
			sx0, sx1 := b.Min.X+x*sw/dw, b.Min.X+(x+1)*sw/dw
			if sx1 <= sx0 {
				sx1 = sx0 + 1
			}
			var rSum, gSum, bSum, aSum, n uint32
			for sy := sy0; sy < sy1 && sy < b.Max.Y; sy++ {
				for sx := sx0; sx < sx1 && sx < b.Max.X; sx++ {
					pr, pg, pb, pa := src.At(sx, sy).RGBA()
					rSum += pr
					gSum += pg
					bSum += pb
					aSum += pa
					n++
				}
			}
			if n == 0 {
				n = 1
			}
			dst.Set(x, y, color.RGBA{
				R: uint8((rSum / n) >> 8), G: uint8((gSum / n) >> 8),
				B: uint8((bSum / n) >> 8), A: uint8((aSum / n) >> 8),
			})
		}
	}
	return dst
}
