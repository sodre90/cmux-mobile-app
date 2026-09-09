package wire

// VersionResponse is the body of `GET /version`: which build of the agent the
// app is actually talking to.
//
// It exists because the two halves ship separately. The app updates from a
// release APK and the agent from a binary on the Mac, so "which version am I
// running" has two answers and nothing surfaced either of them -- the bridge
// knew its own version only as a CLI subcommand nobody runs mid-session, and
// the phone had no way to ask.
//
// Only the agent's version is here. The app knows its own from BuildConfig
// without a round trip, and the relay is deliberately not reported: it is a
// blind transport the app never addresses directly, and naming it here would
// invite treating it as part of the app-facing contract.
type VersionResponse struct {
	Bridge string `json:"bridge"`
}
