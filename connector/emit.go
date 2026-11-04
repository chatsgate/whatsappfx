package main

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"sync"
)

var (
	outMu  sync.Mutex
	outBuf = bufio.NewWriterSize(os.Stdout, 1<<20)
)

type reply struct {
	ID     string          `json:"id"`
	OK     bool            `json:"ok"`
	Result any             `json:"result,omitempty"`
	Error  string          `json:"error,omitempty"`
	Raw    json.RawMessage `json:"-"`
}

type event struct {
	Event string `json:"event"`
	Data  any    `json:"data,omitempty"`
}

func writeLine(v any) {
	b, err := json.Marshal(v)
	if err != nil {
		logf("marshal failed: %v", err)
		return
	}
	outMu.Lock()
	defer outMu.Unlock()
	outBuf.Write(b)
	outBuf.WriteByte('\n')
	outBuf.Flush()
}

func emit(name string, data any) {
	writeLine(event{Event: name, Data: data})
}

func ok(id string, result any) {
	writeLine(reply{ID: id, OK: true, Result: result})
}

func fail(id string, err error) {
	writeLine(reply{ID: id, OK: false, Error: err.Error()})
}

func logf(format string, args ...any) {
	fmt.Fprintf(os.Stderr, format+"\n", args...)
}

func errorf(format string, args ...any) error {
	return fmt.Errorf(format, args...)
}

// Every event that changes whether we are paired or online carries the whole session state, because
// the daemon's own copy is otherwise only refreshed on 'ready' and 'connected' — which is how it came
// to report "loggedIn": true for two days after WhatsApp had removed the device. Extras are applied
// last so a caller can state a truth the client has not caught up with yet.
func (s *session) emitState(name string, extra map[string]any) {
	m := s.status()
	for k, v := range extra {
		m[k] = v
	}
	emit(name, m)
}
