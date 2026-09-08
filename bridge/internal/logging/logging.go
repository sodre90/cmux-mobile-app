// Package logging installs the one shared slog handler every bridge and
// relay log call site writes through, so both binaries emit a single
// consistent structured format instead of each package picking its own.
package logging

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"

	lumberjack "gopkg.in/natefinch/lumberjack.v2"
)

// Rotation bounds for the agent's own log file.
const (
	maxLogSizeMB   = 8
	maxLogBackups  = 5
	maxLogAgeDays  = 30
	logDirMode     = 0o755
	compressRolled = true
)

// Init installs the shared handler as the process-wide default logger. Call
// once from main(), before any subcommand runs.
func Init() {
	setHandler(os.Stderr)
}

// UseRotatingFile redirects the shared handler at path, rolling the file at
// [maxLogSizeMB] and keeping [maxLogBackups] compressed generations. An empty
// path leaves the logger on stderr.
//
// The agent runs under launchd, which holds a plain append fd on whatever
// StandardOutPath names and never truncates or rolls it -- the file reached
// 6.1MB by 2026-09-08, 6001 lines of it a single repeated dial failure
// (cmux-app-wrt). newsyslog is the macOS-native answer but wants root to
// install and a SIGHUP reopen this agent does not implement, so the agent
// owns its log file instead and the plist points launchd's own stdout/stderr
// at a separate file that only ever sees panics and pre-config startup
// errors.
//
// Called after the config load rather than from Init, so anything that fails
// before there is a config to read still reaches launchd's log.
func UseRotatingFile(path string) error {
	if path == "" {
		return nil
	}
	if err := os.MkdirAll(filepath.Dir(path), logDirMode); err != nil {
		return err
	}
	setHandler(&lumberjack.Logger{
		Filename:   path,
		MaxSize:    maxLogSizeMB,
		MaxBackups: maxLogBackups,
		MaxAge:     maxLogAgeDays,
		Compress:   compressRolled,
	})
	return nil
}

func setHandler(w io.Writer) {
	slog.SetDefault(slog.New(slog.NewTextHandler(w, nil)))
}
