package main

import (
	"context"
	"os"
	"path/filepath"
	"sync"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/types/events"
)

type pendingRetry struct {
	mediaKey, encHash, fileHash []byte
	mediaType                   whatsmeow.MediaType
	out, kind                   string
}

var (
	retryMu sync.Mutex
	retries = map[string]pendingRetry{}
)

func rememberRetry(id string, p pendingRetry) {
	retryMu.Lock()
	defer retryMu.Unlock()
	retries[id] = p
}

func takeRetry(id string) (pendingRetry, bool) {
	retryMu.Lock()
	defer retryMu.Unlock()
	p, found := retries[id]
	delete(retries, id)
	return p, found
}

// The phone answers a retry receipt with a fresh directPath rather than the file, so completing the
// download is our job and it happens here rather than back in the daemon.
func (s *session) onMediaRetry(e *events.MediaRetry) {
	p, found := takeRetry(e.MessageID)
	if !found {
		emit("mediaretry", map[string]any{"id": e.MessageID, "status": "unexpected"})
		return
	}
	notif, err := whatsmeow.DecryptMediaRetryNotification(e, p.mediaKey)
	if err != nil {
		emit("mediaretry", map[string]any{"id": e.MessageID, "status": "failed", "error": err.Error()})
		return
	}
	if notif.GetResult() != 0 || notif.GetDirectPath() == "" {
		emit("mediaretry", map[string]any{"id": e.MessageID, "status": "refused", "result": notif.GetResult().String()})
		return
	}
	if err := s.finishRetry(e.MessageID, p, notif.GetDirectPath()); err != nil {
		emit("mediaretry", map[string]any{"id": e.MessageID, "status": "failed", "error": err.Error()})
	}
}

func (s *session) finishRetry(id string, p pendingRetry, directPath string) error {
	if err := os.MkdirAll(filepath.Dir(p.out), 0o755); err != nil {
		return err
	}
	tmp := p.out + ".part"
	f, err := os.Create(tmp)
	if err != nil {
		return err
	}
	err = s.cli().DownloadMediaWithPathToFile(context.Background(), directPath,
		p.encHash, p.fileHash, p.mediaKey, p.mediaType, "", false, f)
	f.Close()
	if err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, p.out); err != nil {
		return err
	}
	sum, size, err := digest(p.out)
	if err != nil {
		return err
	}
	emit("mediaready", map[string]any{"id": id, "path": p.out, "size": size, "sha256": sum, "kind": p.kind})
	return nil
}
