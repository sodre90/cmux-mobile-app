// Package cmux is the only seam that talks to cmux. It shells out to the
// documented cmux CLI: `cmux rpc <method> [json]` for control calls and
// `cmux events <args...>` for the NDJSON event stream. The bridge relies on the
// CLI's local auto-resolution of the socket path and password, so no socket
// credentials are handled here.
package cmux

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os/exec"
)

// Client invokes the cmux CLI. The zero value uses "cmux" from PATH.
type Client struct {
	// Bin is the path to the cmux binary. When empty, "cmux" is used.
	Bin string
}

func (c *Client) bin() string {
	if c.Bin == "" {
		return "cmux"
	}
	return c.Bin
}

// Rpc runs `cmux rpc <method> [json(params)]` and returns stdout as raw JSON.
// On a non-zero exit, the returned error includes the captured stderr.
func (c *Client) Rpc(ctx context.Context, method string, params any) (json.RawMessage, error) {
	args := []string{"rpc", method}
	if params != nil {
		b, err := json.Marshal(params)
		if err != nil {
			return nil, fmt.Errorf("marshal params: %w", err)
		}
		args = append(args, string(b))
	}
	cmd := exec.CommandContext(ctx, c.bin(), args...)
	cmd.Env = append(cmd.Environ(), "CMUX_QUIET=1")
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil {
		return nil, fmt.Errorf("cmux rpc %s: %w: %s", method, err, stderr.String())
	}
	return json.RawMessage(stdout.Bytes()), nil
}

// Events starts `cmux events <args...>` and returns the running command plus a
// reader over its stdout (newline-delimited JSON). Stop it by cancelling ctx and
// closing the reader; the caller is responsible for cmd.Wait.
func (c *Client) Events(ctx context.Context, args ...string) (*exec.Cmd, io.ReadCloser, error) {
	cmd := exec.CommandContext(ctx, c.bin(), append([]string{"events"}, args...)...)
	cmd.Env = append(cmd.Environ(), "CMUX_QUIET=1")
	pipe, err := cmd.StdoutPipe()
	if err != nil {
		return nil, nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, nil, err
	}
	return cmd, pipe, nil
}
