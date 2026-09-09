package wire

import "testing"

func TestFCMClientConfigConfigured(t *testing.T) {
	full := FCMClientConfig{ProjectID: "p", AppID: "a", APIKey: "k", SenderID: "s"}
	if !full.Configured() {
		t.Fatal("a fully populated config must be Configured")
	}
	if full.PartiallyConfigured() {
		t.Fatal("a fully populated config is not partial")
	}
	// Every single-field omission must read as unconfigured: Firebase rejects
	// partial options, so three of four is worth nothing to a phone.
	for name, c := range map[string]FCMClientConfig{
		"no project_id": {AppID: "a", APIKey: "k", SenderID: "s"},
		"no app_id":     {ProjectID: "p", APIKey: "k", SenderID: "s"},
		"no api_key":    {ProjectID: "p", AppID: "a", SenderID: "s"},
		"no sender_id":  {ProjectID: "p", AppID: "a", APIKey: "k"},
	} {
		if c.Configured() {
			t.Fatalf("%s: must not be Configured", name)
		}
		if !c.PartiallyConfigured() {
			t.Fatalf("%s: must be PartiallyConfigured so the operator is warned", name)
		}
	}
}

func TestFCMClientConfigZeroValueIsNeitherConfiguredNorPartial(t *testing.T) {
	var c FCMClientConfig
	if c.Configured() {
		t.Fatal("zero value must not be Configured")
	}
	// Nothing set is a deliberate "no push here", not a half-finished job, so
	// it must not produce a warning.
	if c.PartiallyConfigured() {
		t.Fatal("zero value must not warn")
	}
}
