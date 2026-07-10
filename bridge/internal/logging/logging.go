// Package logging installs the one shared slog handler every bridge and
// relay log call site writes through, so both binaries emit a single
// consistent structured format instead of each package picking its own.
package logging

import (
	"log/slog"
	"os"
)

// Init installs the shared handler as the process-wide default logger. Call
// once from main(), before any subcommand runs.
func Init() {
	slog.SetDefault(slog.New(slog.NewTextHandler(os.Stderr, nil)))
}
