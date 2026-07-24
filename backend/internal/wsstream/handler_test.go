package wsstream

import (
	"testing"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/domain"
)

func TestValidateBootstrapAllowsNonStaleTerminalReplay(t *testing.T) {
	for _, state := range []string{"closed", "expired"} {
		err := validateBootstrap(domain.StreamBootstrap{
			AcceptedAfter:   3,
			CurrentSequence: 5,
			SessionState:    state,
		}, 3)
		if err != nil {
			t.Fatalf("%s bootstrap rejected: %v", state, err)
		}
	}
}

func TestValidateBootstrapRejectsStaleTerminalSnapshot(t *testing.T) {
	for _, state := range []string{"closed", "expired"} {
		err := validateBootstrap(domain.StreamBootstrap{
			AcceptedAfter:   0,
			CurrentSequence: 5,
			Stale:           true,
			SessionState:    state,
		}, 0)
		problem := domain.AsError(err)
		if problem.Kind != domain.ErrorNotFound ||
			problem.Code != "NOT_FOUND" ||
			problem.Detail != "Requested gateway resource was not found" {
			t.Fatalf("%s stale terminal bootstrap error = %#v", state, problem)
		}
	}
}
