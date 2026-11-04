package main

import (
	"database/sql"
	"encoding/hex"
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

/*
The session database holds the linked device's identity and Signal keys: a copy of the plain file can
act as the device. So it is encrypted with a key the Java side keeps in the operating system's keystore
(DPAPI on Windows, the Keychain on macOS) and hands over in the environment of this process only.

The encryption is the Adiantum VFS of the pure-Go SQLite driver: every page of the database and its
WAL is encrypted, nothing needs a C compiler, and the same code builds for macOS.

KeyEnv is read once and removed from this process's environment, so a child process never inherits it.
*/
const keyEnv = "WHATSAPPFX_DB_KEY"

// storeKey returns the hex key, or "" when the Java side did not give one (a development run): the
// store then stays a plain file, as before.
func storeKey() (string, error) {
	k := strings.TrimSpace(os.Getenv(keyEnv))
	os.Unsetenv(keyEnv)
	if k == "" {
		return "", nil
	}
	b, err := hex.DecodeString(k)
	if err != nil || len(b) != 32 {
		return "", fmt.Errorf("%s is not a 256-bit hex key", keyEnv)
	}
	return k, nil
}

// encryptedPath is where the encrypted store lives: next to the plain one, under its own name, so a
// plain file and an encrypted file are never confused.
func encryptedPath(plain string) string {
	return filepath.Join(filepath.Dir(plain), "whatsmeow.enc.db")
}

func encryptedDSN(path, key string) string {
	return "file:" + filepath.ToSlash(path) + "?vfs=adiantum&hexkey=" + key
}

/*
migrateToEncrypted copies an existing plain store into the encrypted one, once. The copy is checked
(integrity, and the same number of rows in the device table) before the plain file is renamed away,
so a failure leaves the plain store in place and the device still linked. The plain file is renamed,
not deleted: removing it is the user's decision.
*/
func migrateToEncrypted(plain, enc, key string) error {
	if _, err := os.Stat(enc); err == nil {
		return nil
	}
	if _, err := os.Stat(plain); err != nil {
		return nil
	}
	src, err := sql.Open("sqlite3", "file:"+filepath.ToSlash(plain)+"?_pragma=busy_timeout(10000)")
	if err != nil {
		return err
	}
	defer src.Close()
	var want int
	if err := src.QueryRow("select count(*) from whatsmeow_device").Scan(&want); err != nil {
		want = -1 // a store from before pairing may have no tables yet
	}
	tmp := enc + ".tmp"
	os.Remove(tmp)
	if _, err := src.Exec("VACUUM INTO '" + strings.ReplaceAll(encryptedDSN(tmp, key), "'", "''") + "'"); err != nil {
		os.Remove(tmp)
		return fmt.Errorf("encrypting the session store: %w", err)
	}
	src.Close()
	if err := checkEncrypted(tmp, key, want); err != nil {
		os.Remove(tmp)
		return err
	}
	if err := os.Rename(tmp, enc); err != nil {
		return err
	}
	for _, sfx := range []string{"", "-wal", "-shm"} {
		if _, err := os.Stat(plain + sfx); err == nil {
			if err := os.Rename(plain+sfx, plain+sfx+".unencrypted-old"); err != nil {
				return fmt.Errorf("encrypted copy made, but the plain store could not be moved aside: %w", err)
			}
		}
	}
	logf("session store encrypted; the old plain file is %s.unencrypted-old (delete it once the app works)", filepath.Base(plain))
	return nil
}

// checkKey turns SQLite's "file is not a database" into what it means here.
func checkKey(path, key string) error {
	db, err := sql.Open("sqlite3", encryptedDSN(path, key))
	if err != nil {
		return err
	}
	defer db.Close()
	var n int
	if err := db.QueryRow("select count(*) from sqlite_master").Scan(&n); err != nil {
		return fmt.Errorf("the session store key does not open %s (restore the key from its recovery code): %w", filepath.Base(path), err)
	}
	return nil
}

func checkEncrypted(path, key string, want int) error {
	db, err := sql.Open("sqlite3", encryptedDSN(path, key))
	if err != nil {
		return err
	}
	defer db.Close()
	var ic string
	if err := db.QueryRow("pragma integrity_check").Scan(&ic); err != nil || ic != "ok" {
		return fmt.Errorf("the encrypted copy failed its integrity check (%v %s)", err, ic)
	}
	if want >= 0 {
		var got int
		if err := db.QueryRow("select count(*) from whatsmeow_device").Scan(&got); err != nil || got != want {
			return fmt.Errorf("the encrypted copy has %d devices, the plain store %d (%v)", got, want, err)
		}
	}
	return nil
}
