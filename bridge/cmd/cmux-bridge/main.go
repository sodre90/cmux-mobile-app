package main

import (
	"fmt"
	"os"

	"github.com/sodre90/cmux-bridge/internal/version"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "agent":
		os.Exit(runAgent(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-bridge", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-bridge <agent|version> [flags]")
}
