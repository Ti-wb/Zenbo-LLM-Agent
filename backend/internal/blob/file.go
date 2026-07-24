package blob

import (
	"context"
	"crypto/sha256"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// FileStore writes every object through a temporary file in the target
// directory and atomically renames it into place. A successful Put therefore
// never exposes a partial object, including after a process crash.
type FileStore struct {
	root string
}

func NewFileStore(root string) (*FileStore, error) {
	if root == "" {
		return nil, fmt.Errorf("blob root is required")
	}
	absolute, err := filepath.Abs(root)
	if err != nil {
		return nil, fmt.Errorf("resolve blob root: %w", err)
	}
	if err := os.MkdirAll(absolute, 0o750); err != nil {
		return nil, fmt.Errorf("create blob root: %w", err)
	}
	return &FileStore{root: absolute}, nil
}

func (s *FileStore) Put(ctx context.Context, key string, source io.Reader, maxBytes int64) (Object, error) {
	if source == nil {
		return Object{}, fmt.Errorf("blob source is required")
	}
	if maxBytes <= 0 {
		return Object{}, fmt.Errorf("blob max bytes must be positive")
	}
	path, err := s.path(key)
	if err != nil {
		return Object{}, err
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o750); err != nil {
		return Object{}, fmt.Errorf("create blob directory: %w", err)
	}
	temp, err := os.CreateTemp(filepath.Dir(path), ".incoming-*")
	if err != nil {
		return Object{}, fmt.Errorf("create temporary blob: %w", err)
	}
	tempName := temp.Name()
	committed := false
	defer func() {
		_ = temp.Close()
		if !committed {
			_ = os.Remove(tempName)
		}
	}()
	if err := temp.Chmod(0o640); err != nil {
		return Object{}, fmt.Errorf("set blob permissions: %w", err)
	}

	digest := sha256.New()
	reader := &contextReader{ctx: ctx, reader: io.LimitReader(source, maxBytes+1)}
	size, err := io.Copy(io.MultiWriter(temp, digest), reader)
	if err != nil {
		return Object{}, fmt.Errorf("write blob: %w", err)
	}
	if size > maxBytes {
		return Object{}, ErrTooLarge
	}
	if err := ctx.Err(); err != nil {
		return Object{}, err
	}
	if err := temp.Sync(); err != nil {
		return Object{}, fmt.Errorf("sync blob: %w", err)
	}
	if err := temp.Close(); err != nil {
		return Object{}, fmt.Errorf("close blob: %w", err)
	}
	if err := os.Rename(tempName, path); err != nil {
		return Object{}, fmt.Errorf("commit blob: %w", err)
	}
	committed = true

	var sum [32]byte
	copy(sum[:], digest.Sum(nil))
	info, err := os.Stat(path)
	if err != nil {
		return Object{}, fmt.Errorf("stat committed blob: %w", err)
	}
	object := Object{
		Key:       key,
		Size:      size,
		SHA256:    sum,
		CreatedAt: info.ModTime().UTC(),
	}
	if err := syncDirectory(filepath.Dir(path)); err != nil {
		// A rename without a durable directory entry would leave a byte object
		// with no metadata row because callers correctly treat this as a failed
		// Put. Remove it when possible; if removal also fails, return the object
		// metadata alongside the error so a caller can explicitly compensate.
		removeErr := os.Remove(path)
		if removeErr == nil {
			_ = syncDirectory(filepath.Dir(path))
			return Object{}, fmt.Errorf("sync blob directory: %w", err)
		}
		return object, fmt.Errorf(
			"sync blob directory: %w (remove committed blob: %v)",
			err, removeErr,
		)
	}
	return object, nil
}

func (s *FileStore) Open(ctx context.Context, key string) (io.ReadCloser, Object, error) {
	if err := ctx.Err(); err != nil {
		return nil, Object{}, err
	}
	path, err := s.path(key)
	if err != nil {
		return nil, Object{}, err
	}
	file, err := os.Open(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, Object{}, ErrNotFound
	}
	if err != nil {
		return nil, Object{}, fmt.Errorf("open blob: %w", err)
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return nil, Object{}, fmt.Errorf("stat blob: %w", err)
	}
	return file, Object{
		Key:       key,
		Size:      info.Size(),
		CreatedAt: info.ModTime().UTC(),
	}, nil
}

func (s *FileStore) Delete(ctx context.Context, key string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	path, err := s.path(key)
	if err != nil {
		return err
	}
	err = os.Remove(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil
	}
	if err != nil {
		return fmt.Errorf("delete blob: %w", err)
	}
	if err := syncDirectory(filepath.Dir(path)); err != nil {
		return fmt.Errorf("sync blob directory: %w", err)
	}
	return nil
}

// SweepIncomplete removes stale temporary files left by process death during
// Put. WalkDir does not follow symlinked directories, and only implementation-
// owned .incoming-* basenames are eligible.
func (s *FileStore) SweepIncomplete(ctx context.Context, olderThan time.Time, limit int) (int, error) {
	if limit <= 0 {
		return 0, fmt.Errorf("blob sweep limit must be positive")
	}
	removed := 0
	err := filepath.WalkDir(s.root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if path == s.root {
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.IsDir() || !strings.HasPrefix(entry.Name(), ".incoming-") {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.ModTime().Before(olderThan) {
			return nil
		}
		if err := os.Remove(path); err != nil && !errors.Is(err, fs.ErrNotExist) {
			return err
		}
		if err := syncDirectory(filepath.Dir(path)); err != nil {
			return err
		}
		removed++
		if removed >= limit {
			return fs.SkipAll
		}
		return nil
	})
	if err != nil {
		return removed, fmt.Errorf("sweep incomplete blobs: %w", err)
	}
	return removed, nil
}

// ListFinalObjects returns committed files old enough for a database orphan
// check. It never returns temporary files or follows symlinks.
func (s *FileStore) ListFinalObjects(
	ctx context.Context,
	olderThan time.Time,
	limit int,
) ([]Object, error) {
	if limit <= 0 {
		return nil, fmt.Errorf("blob list limit must be positive")
	}
	result := make([]Object, 0, limit)
	err := filepath.WalkDir(s.root, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return walkErr
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if path == s.root {
			return nil
		}
		if entry.Type()&os.ModeSymlink != 0 {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.IsDir() ||
			strings.HasPrefix(entry.Name(), ".incoming-") ||
			!entry.Type().IsRegular() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return err
		}
		if !info.ModTime().Before(olderThan) {
			return nil
		}
		key, err := filepath.Rel(s.root, path)
		if err != nil {
			return err
		}
		result = append(result, Object{
			Key:       filepath.ToSlash(key),
			Size:      info.Size(),
			CreatedAt: info.ModTime().UTC(),
		})
		if len(result) >= limit {
			return fs.SkipAll
		}
		return nil
	})
	if err != nil {
		return nil, fmt.Errorf("list committed blobs: %w", err)
	}
	return result, nil
}

// Key creates a stable, traversal-safe object key. Identifiers are required to
// be UUID-like values supplied by the application, never user filenames.
func Key(sessionID, artifactID, extension string) (string, error) {
	if !safeSegment(sessionID) || !safeSegment(artifactID) {
		return "", ErrInvalidKey
	}
	extension = strings.TrimPrefix(extension, ".")
	if extension == "" || !safeSegment(extension) {
		return "", ErrInvalidKey
	}
	return filepath.ToSlash(filepath.Join(sessionID, artifactID+"."+extension)), nil
}

func (s *FileStore) path(key string) (string, error) {
	if key == "" || filepath.IsAbs(key) || strings.ContainsRune(key, '\x00') {
		return "", ErrInvalidKey
	}
	clean := filepath.Clean(filepath.FromSlash(key))
	if clean == "." || clean == ".." || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", ErrInvalidKey
	}
	path := filepath.Join(s.root, clean)
	relative, err := filepath.Rel(s.root, path)
	if err != nil || relative == ".." || strings.HasPrefix(relative, ".."+string(filepath.Separator)) {
		return "", ErrInvalidKey
	}
	return path, nil
}

func safeSegment(value string) bool {
	if value == "" || value == "." || value == ".." {
		return false
	}
	for _, character := range value {
		if (character >= 'a' && character <= 'z') ||
			(character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') ||
			character == '-' || character == '_' {
			continue
		}
		return false
	}
	return true
}

func syncDirectory(path string) error {
	directory, err := os.Open(path)
	if err != nil {
		return err
	}
	defer directory.Close()
	return directory.Sync()
}

type contextReader struct {
	ctx    context.Context
	reader io.Reader
}

func (r *contextReader) Read(buffer []byte) (int, error) {
	if err := r.ctx.Err(); err != nil {
		return 0, err
	}
	return r.reader.Read(buffer)
}

var _ Store = (*FileStore)(nil)
var _ IncompleteSweeper = (*FileStore)(nil)
var _ FinalObjectLister = (*FileStore)(nil)
