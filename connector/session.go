package main

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"

	"go.mau.fi/whatsmeow"
	"go.mau.fi/whatsmeow/proto/waCompanionReg"
	"go.mau.fi/whatsmeow/store"
	"go.mau.fi/whatsmeow/store/sqlstore"
	waLog "go.mau.fi/whatsmeow/util/log"
	"google.golang.org/protobuf/proto"

	_ "github.com/ncruces/go-sqlite3/driver"
	_ "github.com/ncruces/go-sqlite3/vfs/adiantum"
)

/*
The client is held behind an atomic pointer rather than in a plain field because it has to be
REPLACED while the process runs.

When WhatsApp removes a linked device, whatsmeow deletes the local device itself: Store.Delete nils
the JID, sets Deleted and swaps every sub-store for one that answers ErrDeviceDeleted. The Client
built at startup keeps pointing at that corpse, Connect refuses on the Deleted flag before it does
anything, and pairing — the one command that has to work in exactly this situation — fails with
"invalid use of deleted device" for the life of the process. Measured here: the app sat in that state
for two days, and only killing it cleared it.

There is no reviving a deleted device, so recovery is a fresh Device and a fresh Client, which means
swapping the pointer under readers. Reads are lock-free; rearm holds resetMu so two of them cannot
interleave.
*/
type session struct {
	ref       atomic.Pointer[whatsmeow.Client]
	container *sqlstore.Container
	spoolDir  string
	resetMu   sync.Mutex

	lastJid      atomic.Value
	logoutReason atomic.Value
	logoutAt     atomic.Int64
	banReason    atomic.Value
	banUntil     atomic.Int64
}

func (s *session) cli() *whatsmeow.Client { return s.ref.Load() }

// With a key (always, when started by the app) the store is the encrypted file and a plain one is
// migrated into it once; without a key (a development run) it stays the plain file.
func openStore(dbPath string) (*sqlstore.Container, error) {
	if err := os.MkdirAll(filepath.Dir(dbPath), 0o700); err != nil {
		return nil, err
	}
	key, err := storeKey()
	if err != nil {
		return nil, err
	}
	const pragmas = "_pragma=foreign_keys(1)&_pragma=journal_mode(WAL)&_pragma=busy_timeout(10000)"
	dsn := "file:" + filepath.ToSlash(dbPath) + "?" + pragmas
	if key != "" {
		enc := encryptedPath(dbPath)
		if err := migrateToEncrypted(dbPath, enc, key); err != nil {
			return nil, err
		}
		if _, err := os.Stat(enc); err == nil {
			if err := checkKey(enc, key); err != nil {
				return nil, err
			}
		}
		dsn = encryptedDSN(enc, key) + "&" + pragmas
	}
	db, err := sql.Open("sqlite3", dsn)
	if err != nil {
		return nil, err
	}
	c := sqlstore.NewWithDB(db, "sqlite3", waLog.Noop)
	if err := c.Upgrade(context.Background()); err != nil {
		return nil, fmt.Errorf("schema upgrade: %w", err)
	}
	return c, nil
}

// Device props reach the server only in the pairing payload, so every value here is decided once
// per linked device and cannot be revised without unlinking.
func applyDeviceProps(cfg *config) {
	store.DeviceProps.Os = proto.String(cfg.DeviceName)
	store.DeviceProps.PlatformType = waCompanionReg.DeviceProps_DESKTOP.Enum()
	store.DeviceProps.RequireFullSync = proto.Bool(cfg.FullSync)
	hs := store.DeviceProps.HistorySyncConfig
	if cfg.SyncDays > 0 {
		hs.FullSyncDaysLimit = proto.Uint32(uint32(cfg.SyncDays))
	}
	hs.StorageQuotaMb = proto.Uint32(uint32(cfg.QuotaMb))
	hs.OnDemandReady = proto.Bool(true)
	hs.CompleteOnDemandReady = proto.Bool(true)
}

func newSession(cfg *config) (*session, error) {
	applyDeviceProps(cfg)
	container, err := openStore(cfg.StorePath)
	if err != nil {
		return nil, err
	}
	dev, err := container.GetFirstDevice(context.Background())
	if err != nil {
		return nil, fmt.Errorf("device load: %w", err)
	}
	if err := os.MkdirAll(cfg.SpoolDir, 0o755); err != nil {
		return nil, err
	}
	s := &session{container: container, spoolDir: cfg.SpoolDir}
	s.ref.Store(s.newClient(dev))
	s.rememberJid(dev)
	return s, nil
}

func (s *session) newClient(dev *store.Device) *whatsmeow.Client {
	c := whatsmeow.NewClient(dev, waLog.Noop)
	c.AddEventHandler(s.onEvent)
	c.AutoTrustIdentity = true
	return c
}

// Kept across a logout on purpose: the daemon files attachments under the account's own number, and
// losing it the moment WhatsApp drops the link would refile them under a placeholder folder.
func (s *session) rememberJid(dev *store.Device) {
	if dev != nil && dev.ID != nil {
		s.lastJid.Store(dev.ID.String())
	}
}

func loadStr(v atomic.Value) string {
	got, _ := v.Load().(string)
	return got
}

func (s *session) status() map[string]any {
	c := s.cli()
	id := c.Store.ID
	m := map[string]any{
		"loggedIn":      id != nil,
		"connected":     c.IsConnected(),
		"needsPairing":  id == nil,
		"deviceDeleted": c.Store.Deleted,
	}
	if id != nil {
		m["jid"] = id.String()
		m["user"] = id.User
		m["pushName"] = c.Store.PushName
	} else if last := loadStr(s.lastJid); last != "" {
		m["lastJid"] = last
	}
	if lid := c.Store.GetLID(); !lid.IsEmpty() {
		m["lid"] = lid.String()
	}
	if r := loadStr(s.logoutReason); r != "" {
		m["logoutReason"] = r
		m["logoutAt"] = s.logoutAt.Load()
	}
	if b := loadStr(s.banReason); b != "" {
		m["banReason"] = b
		m["banUntil"] = s.banUntil.Load()
	}
	return m
}
