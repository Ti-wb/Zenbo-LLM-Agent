package blob

import (
	"bytes"
	"context"
	"crypto/sha256"
	"errors"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"
)

func TestFileStorePutOpenDelete(t *testing.T) {
	store, err := NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	key, err := Key(
		"10000000-0000-4000-8000-000000000001",
		"20000000-0000-4000-8000-000000000002",
		"wav",
	)
	if err != nil {
		t.Fatal(err)
	}
	content := []byte("RIFF-test-WAVE")
	object, err := store.Put(context.Background(), key, bytes.NewReader(content), 1024)
	if err != nil {
		t.Fatal(err)
	}
	if object.Size != int64(len(content)) {
		t.Fatalf("size = %d", object.Size)
	}
	if expected := sha256.Sum256(content); object.SHA256 != expected {
		t.Fatalf("digest = %x, want %x", object.SHA256, expected)
	}

	reader, opened, err := store.Open(context.Background(), key)
	if err != nil {
		t.Fatal(err)
	}
	defer reader.Close()
	actual, err := io.ReadAll(reader)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(actual, content) || opened.Size != int64(len(content)) {
		t.Fatalf("opened content or metadata differs")
	}
	if err := store.Delete(context.Background(), key); err != nil {
		t.Fatal(err)
	}
	if _, _, err := store.Open(context.Background(), key); !errors.Is(err, ErrNotFound) {
		t.Fatalf("open after delete = %v, want ErrNotFound", err)
	}
}

func TestFileStoreRejectsTraversalAndOversizeWithoutPartialFile(t *testing.T) {
	root := t.TempDir()
	store, err := NewFileStore(root)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Key("../outside", "artifact", "wav"); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("Key traversal = %v", err)
	}
	if _, err := store.Put(context.Background(), "../outside", bytes.NewReader([]byte("x")), 1); !errors.Is(err, ErrInvalidKey) {
		t.Fatalf("Put traversal = %v", err)
	}
	key := "session/artifact.wav"
	if _, err := store.Put(context.Background(), key, bytes.NewReader([]byte("too large")), 3); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("oversize Put = %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, key)); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("partial target exists: %v", err)
	}
	matches, err := filepath.Glob(filepath.Join(root, "session", ".incoming-*"))
	if err != nil {
		t.Fatal(err)
	}
	if len(matches) != 0 {
		t.Fatalf("temporary files were not cleaned: %v", matches)
	}
}

func TestFileStoreHonorsCancelledContext(t *testing.T) {
	store, err := NewFileStore(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err = store.Put(ctx, "session/artifact.wav", bytes.NewReader([]byte("content")), 1024)
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("Put = %v, want context.Canceled", err)
	}
}

func TestFileStoreSweepsOnlyOldIncompleteFiles(t *testing.T) {
	root := t.TempDir()
	store, err := NewFileStore(root)
	if err != nil {
		t.Fatal(err)
	}
	directory := filepath.Join(root, "session")
	if err := os.MkdirAll(directory, 0o750); err != nil {
		t.Fatal(err)
	}
	old := filepath.Join(directory, ".incoming-old")
	fresh := filepath.Join(directory, ".incoming-fresh")
	ordinary := filepath.Join(directory, "artifact.wav")
	for _, path := range []string{old, fresh, ordinary} {
		if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Now()
	if err := os.Chtimes(old, now.Add(-time.Hour), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	removed, err := store.SweepIncomplete(
		context.Background(), now.Add(-time.Minute), 10,
	)
	if err != nil {
		t.Fatal(err)
	}
	if removed != 1 {
		t.Fatalf("removed = %d", removed)
	}
	if _, err := os.Stat(old); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("old temp still exists: %v", err)
	}
	for _, path := range []string{fresh, ordinary} {
		if _, err := os.Stat(path); err != nil {
			t.Fatalf("%s was removed: %v", path, err)
		}
	}
}

func TestFileStoreListsOnlyOldCommittedObjects(t *testing.T) {
	root := t.TempDir()
	store, err := NewFileStore(root)
	if err != nil {
		t.Fatal(err)
	}
	directory := filepath.Join(root, "session")
	if err := os.MkdirAll(directory, 0o750); err != nil {
		t.Fatal(err)
	}
	old := filepath.Join(directory, "old.wav")
	fresh := filepath.Join(directory, "fresh.wav")
	incomplete := filepath.Join(directory, ".incoming-orphan")
	for _, path := range []string{old, fresh, incomplete} {
		if err := os.WriteFile(path, []byte("x"), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Now()
	if err := os.Chtimes(old, now.Add(-time.Hour), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if err := os.Chtimes(incomplete, now.Add(-time.Hour), now.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}

	objects, err := store.ListFinalObjects(
		context.Background(), now.Add(-time.Minute), 10,
	)
	if err != nil {
		t.Fatal(err)
	}
	if len(objects) != 1 || objects[0].Key != "session/old.wav" ||
		objects[0].Size != 1 {
		t.Fatalf("objects = %#v", objects)
	}
}
