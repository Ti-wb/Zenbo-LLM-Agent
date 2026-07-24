package application

import (
	"context"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"fmt"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/auth"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

type DeviceAuthenticator struct {
	repository Repository
	hmacKey    []byte
	clock      func() time.Time
}

func NewDeviceAuthenticator(repository Repository, hmacKey []byte, clock func() time.Time) (*DeviceAuthenticator, error) {
	if repository == nil {
		return nil, fmt.Errorf("device repository is required")
	}
	if len(hmacKey) < 32 {
		return nil, fmt.Errorf("device token HMAC key must contain at least 32 bytes")
	}
	if clock == nil {
		clock = time.Now
	}
	return &DeviceAuthenticator{
		repository: repository,
		hmacKey:    append([]byte(nil), hmacKey...),
		clock:      clock,
	}, nil
}

func (authenticator *DeviceAuthenticator) Authenticate(
	ctx context.Context,
	token, deviceID string,
) (domain.Principal, error) {
	device, err := authenticator.repository.AuthenticateAndBindDevice(
		ctx, authenticator.digest(token), deviceID, authenticator.clock().UTC(),
	)
	if err != nil {
		return domain.Principal{}, publicError(err)
	}
	return domain.Principal{TokenID: device.ID, DeviceID: device.DeviceID}, nil
}

// Issue creates a cryptographically random token and persists only its keyed
// digest. The plaintext return value is intentionally impossible to recover.
func (authenticator *DeviceAuthenticator) Issue(ctx context.Context, label string) (Device, string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return Device{}, "", fmt.Errorf("generate device token: %w", err)
	}
	token := base64.RawURLEncoding.EncodeToString(raw[:])
	device, err := authenticator.repository.IssueDevice(
		ctx, authenticator.digest(token), label, authenticator.clock().UTC(),
	)
	if err != nil {
		return Device{}, "", err
	}
	return device, token, nil
}

func (authenticator *DeviceAuthenticator) Revoke(ctx context.Context, deviceRecordID string) error {
	return authenticator.repository.RevokeDevice(
		ctx, deviceRecordID, authenticator.clock().UTC(),
	)
}

func (authenticator *DeviceAuthenticator) digest(token string) []byte {
	mac := hmac.New(sha256.New, authenticator.hmacKey)
	_, _ = mac.Write([]byte(token))
	return mac.Sum(nil)
}

var _ auth.Authenticator = (*DeviceAuthenticator)(nil)
