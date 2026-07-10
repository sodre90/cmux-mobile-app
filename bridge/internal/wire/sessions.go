package wire

// Workspace is the app-facing representation of a cmux workspace and its
// terminal surfaces (panes). Each pane's ID is a streamable terminal-surface id
// the app opens via /terminal/{id}.
type Workspace struct {
	ID        string         `json:"id"`
	CWD       string         `json:"cwd"`
	Title     string         `json:"title"`
	Preview   string         `json:"preview"`
	HasUnread bool           `json:"has_unread"`
	Attention string         `json:"attention,omitempty"`
	YoloMode  string         `json:"yolo_mode,omitempty"`
	Terminals []TerminalPane `json:"terminals"`
}

// TerminalPane is one terminal surface within a workspace. ID is the cmux
// terminal-surface id; Kind is a cosmetic badge derived from the title.
type TerminalPane struct {
	ID      string `json:"id"`
	CWD     string `json:"cwd"`
	Title   string `json:"title"`
	Focused bool   `json:"focused"`
	Ready   bool   `json:"ready"`
	Kind    string `json:"kind"`
}
