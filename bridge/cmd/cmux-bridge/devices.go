package main

import (
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"text/tabwriter"
	"time"

	"github.com/sodre90/cmux-bridge/internal/config"
	"github.com/sodre90/cmux-bridge/internal/e2e"
	"github.com/sodre90/cmux-bridge/internal/wire"
)

// deviceAdminTimeout bounds a single device-admin HTTP call. These are small
// SQLite reads and one delete; anything slower than this is a dead server,
// not a busy one.
const deviceAdminTimeout = 10 * time.Second

// displayedHashLen is how much of a token hash the listing prints. It is the
// operator's only source for the prefix `revoke` takes, so it has to be wide
// enough that what they can see is never ambiguous.
const displayedHashLen = 12

// localSource marks a row that exists only in this Mac's e2e store: a shared
// secret whose server-side token is already gone. Those are unusable and
// invisible from the server side, so the listing has to carry them or
// nothing can ever reap them.
const localSource = "local"

// deviceRow is one paired device as the operator sees it: a server's view of
// it joined to whether this Mac still holds the e2e secret that makes it
// usable. The two stores drift apart with nothing reconciling them
// (cmux-app-vkq), and this join is the report of that drift.
type deviceRow struct {
	server    agentServer
	device    wire.AgentDevice
	hasSecret bool
}

func fetchDevices(srv agentServer) ([]wire.AgentDevice, error) {
	resp, err := srv.client.Get(srv.baseURL + "/agent/devices")
	if err != nil {
		return nil, err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
	var body wire.AgentDeviceListResp
	if err := json.NewDecoder(resp.Body).Decode(&body); err != nil {
		return nil, err
	}
	return body.Devices, nil
}

// revokeOnServer deletes the device's bearer token. A device the server has
// never heard of is reported as existed=false rather than as an error: that
// is the orphan case the caller is here to clean up, not a failure.
func revokeOnServer(srv agentServer, tokenHash string) (existed bool, err error) {
	resp, err := srv.client.Post(srv.baseURL+"/agent/devices/"+tokenHash+"/revoke", "application/json", nil)
	if err != nil {
		return false, err
	}
	defer func() { _ = resp.Body.Close() }()
	switch resp.StatusCode {
	case http.StatusOK, http.StatusNoContent:
		return true, nil
	case http.StatusNotFound:
		return false, nil
	default:
		return false, fmt.Errorf("unexpected status %d", resp.StatusCode)
	}
}

// collectDevices joins every configured server's listing to this Mac's e2e
// store. A server that cannot be reached yields a problem string rather than
// aborting the whole listing -- the rows that did come back are still worth
// showing -- so callers that must not act on a partial view check problems
// before doing anything.
func collectDevices(servers []agentServer, sessions *e2e.Store) (rows []deviceRow, problems []string) {
	unclaimedSecrets := make(map[string]bool)
	for _, id := range sessions.DeviceIDs() {
		unclaimedSecrets[id] = true
	}
	for _, srv := range servers {
		devices, err := fetchDevices(srv)
		if err != nil {
			problems = append(problems, srv.kind+": "+err.Error())
			continue
		}
		for _, dev := range devices {
			// device_id in the e2e store IS the auth store's token hash --
			// AddDevice is keyed by exactly what the server returns here.
			hasSecret := unclaimedSecrets[dev.TokenHash]
			delete(unclaimedSecrets, dev.TokenHash)
			rows = append(rows, deviceRow{server: srv, device: dev, hasSecret: hasSecret})
		}
	}
	// Only once every server has answered is a leftover secret really an
	// orphan; against a partial listing it may just belong to the server that
	// failed, and calling it local would invite reaping a live device.
	if len(problems) == 0 {
		for _, id := range sessions.DeviceIDs() {
			if unclaimedSecrets[id] {
				rows = append(rows, deviceRow{
					server:    agentServer{kind: localSource},
					device:    wire.AgentDevice{TokenHash: id},
					hasSecret: true,
				})
			}
		}
	}
	return rows, problems
}

func printDeviceRows(w io.Writer, rows []deviceRow) {
	tw := tabwriter.NewWriter(w, 0, 0, 2, ' ', 0)
	_, _ = fmt.Fprintln(tw, "SOURCE\tDEVICE\tNAME\tCREATED\tSECRET")
	for _, row := range rows {
		_, _ = fmt.Fprintf(tw, "%s\t%s\t%s\t%s\t%s\n",
			row.server.kind, shortHash(row.device.TokenHash),
			orDash(row.device.Name), orDash(row.device.CreatedAt), yesNo(row.hasSecret))
	}
	_ = tw.Flush()
}

func shortHash(tokenHash string) string {
	if len(tokenHash) <= displayedHashLen {
		return tokenHash
	}
	return tokenHash[:displayedHashLen]
}

func orDash(s string) string {
	if s == "" {
		return "-"
	}
	return s
}

func yesNo(b bool) string {
	if b {
		return "yes"
	}
	return "no"
}

// resolveDevicePrefix picks the one device prefix names, and refuses rather
// than guesses: revocation is destructive and the operator types these by
// hand off the listing.
func resolveDevicePrefix(rows []deviceRow, prefix string) (deviceRow, error) {
	if prefix == "" {
		return deviceRow{}, fmt.Errorf("no device given")
	}
	var matches []deviceRow
	for _, row := range rows {
		if row.device.TokenHash == prefix {
			return row, nil
		}
		if strings.HasPrefix(row.device.TokenHash, prefix) {
			matches = append(matches, row)
		}
	}
	switch len(matches) {
	case 1:
		return matches[0], nil
	case 0:
		return deviceRow{}, fmt.Errorf("no device matches %q", prefix)
	default:
		var candidates []string
		for _, row := range matches {
			candidates = append(candidates, shortHash(row.device.TokenHash)+" ("+row.server.kind+")")
		}
		return deviceRow{}, fmt.Errorf("%q matches %d devices, be more specific: %s",
			prefix, len(matches), strings.Join(candidates, ", "))
	}
}

// revokeByPrefix takes both halves of a paired device away: the bearer token
// on the server that issued it, then the shared secret this Mac holds for
// it. That order is load-bearing -- dropping the secret first would leave a
// token that authenticates into an agent which cannot decrypt for it, which
// is the exact drift state this command exists to clean up.
func revokeByPrefix(out io.Writer, servers []agentServer, sessions *e2e.Store, prefix string) error {
	rows, problems := collectDevices(servers, sessions)
	if len(problems) > 0 {
		return fmt.Errorf("refusing to revoke from a partial device listing (%s) -- a prefix could resolve to the wrong device",
			strings.Join(problems, "; "))
	}
	row, err := resolveDevicePrefix(rows, prefix)
	if err != nil {
		return err
	}

	existed := false
	if row.server.kind != localSource {
		existed, err = revokeOnServer(row.server, row.device.TokenHash)
		if err != nil {
			return fmt.Errorf("revoke on %s (nothing was removed): %w", row.server.kind, err)
		}
	}
	removed, err := sessions.RemoveDevice(row.device.TokenHash)
	if err != nil {
		return fmt.Errorf("the %s token is revoked, but removing the local secret failed: %w", row.server.kind, err)
	}
	_, _ = fmt.Fprintf(out, "revoked %s: %s\n", shortHash(row.device.TokenHash),
		describeRevocation(row.server.kind, existed, removed))
	return nil
}

func describeRevocation(source string, existed, secretRemoved bool) string {
	served := source + " token removed"
	switch {
	case source == localSource:
		served = "no server token (this was a stranded local secret)"
	case !existed:
		served = source + " had no such token already"
	}
	if secretRemoved {
		return served + ", local secret removed"
	}
	return served + ", no local secret held"
}

// runDevices implements `cmux-bridge devices`, the operator's view of who is
// paired and the only way to take a pairing back. Both subcommands act
// across every configured server, so the operator never has to know which
// slot a phone was paired through.
func runDevices(args []string) int {
	if len(args) == 0 {
		devicesUsage()
		return 2
	}
	sub, rest := args[0], args[1:]
	if sub != "list" && sub != "revoke" {
		devicesUsage()
		return 2
	}
	fs := flag.NewFlagSet("devices "+sub, flag.ContinueOnError)
	cfgPath := fs.String("config", defaultAgentConfigPath(), "path to agent.toml")
	if err := fs.Parse(rest); err != nil {
		return 2
	}
	cfg, err := config.LoadAgent(*cfgPath)
	if err != nil {
		fmt.Fprintln(os.Stderr, "load agent config:", err)
		return 1
	}
	servers, err := configuredServers(cfg, deviceAdminTimeout)
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	sessions, err := e2e.OpenStore(cfg.SessionStore)
	if err != nil {
		fmt.Fprintln(os.Stderr, "open session store:", err)
		return 1
	}

	if sub == "list" {
		rows, problems := collectDevices(servers, sessions)
		printDeviceRows(os.Stdout, rows)
		for _, problem := range problems {
			fmt.Fprintln(os.Stderr, "could not list devices on", problem)
		}
		if len(problems) > 0 {
			return 1
		}
		return 0
	}
	if len(fs.Args()) != 1 {
		devicesUsage()
		return 2
	}
	if err := revokeByPrefix(os.Stdout, servers, sessions, fs.Args()[0]); err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	return 0
}

func devicesUsage() {
	fmt.Fprintln(os.Stderr, "usage: cmux-bridge devices list [flags]")
	fmt.Fprintln(os.Stderr, "       cmux-bridge devices revoke <device-prefix> [flags]")
}
