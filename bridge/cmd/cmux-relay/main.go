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
	case "serve":
		os.Exit(runServe(os.Args[2:]))
	case "pair":
		os.Exit(runPair(os.Args[2:]))
	case "devices":
		os.Exit(runDevices(os.Args[2:]))
	case "version", "--version", "-v":
		fmt.Println("cmux-relay", version.String())
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-relay <serve|pair|devices|version> [flags]")
}
