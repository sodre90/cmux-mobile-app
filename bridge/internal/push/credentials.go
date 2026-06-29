package push

import (
	"context"
	"fmt"
	"os"

	"golang.org/x/oauth2/google"
)

// fcmScope is the OAuth scope required to send FCM HTTP v1 messages.
const fcmScope = "https://www.googleapis.com/auth/firebase.messaging"

// FromServiceAccount builds a Sender that mints OAuth access tokens for FCM HTTP
// v1 from a Google service-account JSON key file at credentialsPath.
func FromServiceAccount(ctx context.Context, projectID, credentialsPath string) (*Sender, error) {
	key, err := os.ReadFile(credentialsPath)
	if err != nil {
		return nil, fmt.Errorf("read fcm credentials: %w", err)
	}
	creds, err := google.CredentialsFromJSON(ctx, key, fcmScope)
	if err != nil {
		return nil, fmt.Errorf("parse fcm credentials: %w", err)
	}
	return &Sender{
		ProjectID: projectID,
		Token: func(context.Context) (string, error) {
			tok, err := creds.TokenSource.Token()
			if err != nil {
				return "", err
			}
			return tok.AccessToken, nil
		},
	}, nil
}
