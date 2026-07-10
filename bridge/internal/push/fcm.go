// Package push sends agent-attention notifications via the Firebase Cloud
// Messaging HTTP v1 API. The HTTP endpoint and OAuth token are injectable so the
// sender can be unit-tested without contacting Google.
package push

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
)

// Sender posts FCM HTTP v1 messages.
type Sender struct {
	ProjectID string
	// BaseURL defaults to https://fcm.googleapis.com. Override in tests.
	BaseURL string
	// HTTP defaults to http.DefaultClient.
	HTTP *http.Client
	// Token returns a bearer OAuth token with the firebase.messaging scope.
	Token func(context.Context) (string, error)
}

// Send delivers a high-priority data message to one FCM registration token.
// title and body are carried inside the data payload so the app controls
// presentation.
func (s *Sender) Send(ctx context.Context, fcmToken, title, body string, data map[string]string) error {
	d := map[string]string{}
	for k, v := range data {
		d[k] = v
	}
	d["title"] = title
	d["body"] = body

	payload := map[string]any{
		"message": map[string]any{
			"token":   fcmToken,
			"data":    d,
			"android": map[string]any{"priority": "high"},
		},
	}
	b, err := json.Marshal(payload)
	if err != nil {
		return err
	}

	base := s.BaseURL
	if base == "" {
		base = "https://fcm.googleapis.com"
	}
	url := base + "/v1/projects/" + s.ProjectID + "/messages:send"

	req, err := http.NewRequestWithContext(ctx, http.MethodPost, url, bytes.NewReader(b))
	if err != nil {
		return err
	}
	tok, err := s.Token(ctx)
	if err != nil {
		return fmt.Errorf("fcm oauth: %w", err)
	}
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Content-Type", "application/json")

	client := s.HTTP
	if client == nil {
		client = http.DefaultClient
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer func() { _ = resp.Body.Close() }()
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("fcm status %d", resp.StatusCode)
	}
	return nil
}
