package store

import (
	"errors"
	"math"
	"testing"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
)

func TestReplayRangeHasGap(t *testing.T) {
	tests := []struct {
		name     string
		after    uint64
		current  int64
		retained int64
		want     bool
	}{
		{
			name: "empty session",
		},
		{
			name:  "complete requested range",
			after: 3, current: 6, retained: 3,
		},
		{
			name:  "pruned prefix",
			after: 0, current: 6, retained: 2, want: true,
		},
		{
			name:  "internal gap",
			after: 3, current: 6, retained: 2, want: true,
		},
		{
			name:  "cursor ahead",
			after: 7, current: 6, want: true,
		},
		{
			name:    "invalid negative database cursor",
			current: -1, want: true,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if got := replayRangeHasGap(
				test.after, test.current, test.retained,
			); got != test.want {
				t.Fatalf("replayRangeHasGap() = %v, want %v", got, test.want)
			}
		})
	}
}

func TestValidateContiguousEvents(t *testing.T) {
	tests := []struct {
		name   string
		after  uint64
		events []application.Event
		stale  bool
	}{
		{
			name: "empty batch",
		},
		{
			name:  "contiguous batch",
			after: 3,
			events: []application.Event{
				{Sequence: 4},
				{Sequence: 5},
				{Sequence: 6},
			},
		},
		{
			name:   "missing first sequence",
			after:  3,
			events: []application.Event{{Sequence: 5}},
			stale:  true,
		},
		{
			name:  "middle sequence gap",
			after: 3,
			events: []application.Event{
				{Sequence: 4},
				{Sequence: 6},
			},
			stale: true,
		},
		{
			name:   "cursor overflow",
			after:  math.MaxUint64,
			events: []application.Event{{Sequence: 1}},
			stale:  true,
		},
	}
	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			err := validateContiguousEvents(test.after, test.events)
			if got := errors.Is(err, application.ErrReplayStale); got != test.stale {
				t.Fatalf("error = %v, stale = %v, want %v", err, got, test.stale)
			}
		})
	}
}
