package main

import (
	"bufio"
	"encoding/json"
	"flag"
	"os"
	"os/signal"
	"sync"
	"syscall"
)

type config struct {
	StorePath   string
	SpoolDir    string
	DeviceName  string
	FullSync    bool
	SyncDays    int
	QuotaMb     int
	AutoConnect bool
}

const maxConcurrent = 4

func parseFlags() *config {
	c := &config{}
	flag.StringVar(&c.StorePath, "store", "whatsmeow.db", "path to the whatsmeow session database")
	flag.StringVar(&c.SpoolDir, "spool", "spool", "directory for downloaded media before the daemon files it")
	flag.StringVar(&c.DeviceName, "device-name", "whatsappfx", "name shown in the phone's Linked Devices list")
	flag.BoolVar(&c.FullSync, "full-sync", true, "ask the phone for full history at pairing time")
	flag.IntVar(&c.SyncDays, "sync-days", 3650, "history sync day limit requested at pairing time")
	flag.IntVar(&c.QuotaMb, "quota-mb", 10240, "history sync storage quota advertised at pairing time")
	flag.BoolVar(&c.AutoConnect, "connect", true, "connect on startup when already paired")
	flag.Parse()
	return c
}

func main() {
	cfg := parseFlags()
	s, err := newSession(cfg)
	if err != nil {
		logf("fatal: %v", err)
		os.Exit(1)
	}
	if cfg.AutoConnect && s.cli().Store.ID != nil {
		if err := s.connect(); err != nil {
			logf("connect failed: %v", err)
		}
	}
	emit("ready", s.status())

	sig := make(chan os.Signal, 1)
	signal.Notify(sig, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sig
		s.shutdown()
	}()

	s.pump(os.Stdin)
	s.shutdown()
}

// One goroutine per request keeps a slow media download from blocking status calls, bounded so a
// backfill cannot open hundreds of sockets at once.
func (s *session) pump(in *os.File) {
	sem := make(chan struct{}, maxConcurrent)
	var wg sync.WaitGroup
	sc := bufio.NewScanner(in)
	sc.Buffer(make([]byte, 0, 64*1024), 16*1024*1024)
	for sc.Scan() {
		line := sc.Bytes()
		if len(line) == 0 {
			continue
		}
		var r request
		if err := json.Unmarshal(line, &r); err != nil {
			logf("bad request line: %v", err)
			continue
		}
		sem <- struct{}{}
		wg.Add(1)
		go func() {
			defer func() {
				if p := recover(); p != nil {
					fail(r.ID, errorf("panic: %v", p))
				}
				<-sem
				wg.Done()
			}()
			s.handle(r)
		}()
	}
	wg.Wait()
}

func (s *session) shutdown() {
	if s.cli() != nil {
		s.cli().Disconnect()
	}
	outMu.Lock()
	outBuf.Flush()
	outMu.Unlock()
	os.Exit(0)
}
