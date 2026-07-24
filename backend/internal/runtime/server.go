// Package runtime assembles process-level dependencies and owns HTTP server
// lifecycle. Business state remains in application and store packages.
package runtime

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os"
	"sync"
	"sync/atomic"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/application"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/contractassets"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/httpapi"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/providerbridge"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/store"
)

type ServerOptions struct {
	Config   config.Runtime
	Profiles config.File
	Logger   *slog.Logger
}

func Serve(ctx context.Context, options ServerOptions) error {
	if err := options.Config.ValidateForServe(); err != nil {
		return err
	}
	// Fail before opening PostgreSQL or binding either listener when the
	// embedded public contract cannot be parsed and compiled.
	if err := contractassets.Verify(); err != nil {
		return fmt.Errorf("verify embedded contracts: %w", err)
	}
	logger := options.Logger
	if logger == nil {
		logger = slog.New(slog.NewJSONHandler(os.Stderr, nil))
	}

	repository, err := store.Open(ctx, options.Config.DatabaseURL)
	if err != nil {
		return err
	}
	defer repository.Close()
	blobs, err := blob.NewFileStore(options.Config.ArtifactDirectory)
	if err != nil {
		return err
	}
	readiness, err := providerbridge.NewReadiness(options.Profiles, providerbridge.Options{
		CodexBinary:     options.Config.CodexBinary,
		CodexHome:       options.Config.CodexHome,
		CodexWorkingDir: options.Config.CodexWorkingDir,
		ProviderTimeout: options.Config.ProviderTimeout,
	})
	if err != nil {
		return err
	}
	startupContext, startupCancel := context.WithTimeout(ctx, 8*time.Second)
	err = readiness.Ready(startupContext)
	startupCancel()
	if err != nil {
		logger.Warn("gateway starting not ready", "component", "providers")
	}

	profiles := make([]application.Profile, 0, len(options.Profiles.Profiles))
	for _, profile := range options.Profiles.Advertised() {
		profiles = append(profiles, application.Profile{
			ID:              profile.ID,
			DisplayName:     profile.DisplayName,
			Languages:       append([]string(nil), profile.Languages...),
			Default:         profile.IsDefault,
			ProviderKind:    profile.Kind,
			ProviderProfile: profile.ID,
		})
	}
	// Provider execution is worker-only. In particular, an API process must
	// not start a second Codex app-server and attempt to interrupt a turn
	// owned by the worker process.
	service, err := application.NewAdapter(repository, blobs, nil, application.Config{
		Profiles:            profiles,
		SessionTTL:          24 * time.Hour,
		IdempotencyTTL:      24 * time.Hour,
		TranscriptRetention: 7 * 24 * time.Hour,
		InputAudioLifetime:  30 * time.Minute,
		TTSLifetime:         30 * time.Minute,
	})
	if err != nil {
		return err
	}
	authenticator, err := application.NewDeviceAuthenticator(repository, options.Config.DeviceHMACKey, nil)
	if err != nil {
		return err
	}
	api := httpapi.NewHandler(service, authenticator, httpapi.Options{
		PingInterval: 20 * time.Second,
	})

	metrics := NewMetrics()
	publicServer := &http.Server{
		Addr:              options.Config.HTTPAddress,
		Handler:           metrics.Wrap(api),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       75 * time.Second,
		MaxHeaderBytes:    32 << 10,
	}
	checker := CachedChecker{
		TTL: 10 * time.Second,
		Check: func(checkContext context.Context) error {
			if err := repository.Ping(checkContext); err != nil {
				return fmt.Errorf("postgres: %w", err)
			}
			if err := readiness.Ready(checkContext); err != nil {
				return fmt.Errorf("providers: %w", err)
			}
			return nil
		},
	}
	adminServer := &http.Server{
		Addr:              options.Config.AdminAddress,
		Handler:           NewAdminHandler(checker.CheckReady, metrics),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}

	publicListener, err := net.Listen("tcp", publicServer.Addr)
	if err != nil {
		return fmt.Errorf("listen public API: %w", err)
	}
	defer publicListener.Close()
	adminListener, err := net.Listen("tcp", adminServer.Addr)
	if err != nil {
		return fmt.Errorf("listen admin API: %w", err)
	}
	defer adminListener.Close()

	logger.Info("gateway servers ready",
		"public_address", publicListener.Addr().String(),
		"admin_address", adminListener.Addr().String(),
	)
	failures := make(chan error, 2)
	go func() {
		if serveErr := publicServer.Serve(publicListener); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			failures <- fmt.Errorf("public API: %w", serveErr)
		}
	}()
	go func() {
		if serveErr := adminServer.Serve(adminListener); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			failures <- fmt.Errorf("admin API: %w", serveErr)
		}
	}()

	var result error
	select {
	case <-ctx.Done():
	case result = <-failures:
	}
	shutdownContext, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	result = errors.Join(
		result,
		publicServer.Shutdown(shutdownContext),
		adminServer.Shutdown(shutdownContext),
	)
	return result
}

type ReadyCheck func(context.Context) error

func NewAdminHandler(check ReadyCheck, metrics *Metrics) http.Handler {
	if metrics == nil {
		metrics = NewMetrics()
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(response http.ResponseWriter, _ *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		_, _ = response.Write([]byte(`{"status":"ok"}`))
	})
	mux.HandleFunc("GET /readyz", func(response http.ResponseWriter, request *http.Request) {
		response.Header().Set("Content-Type", "application/json")
		if check == nil {
			response.WriteHeader(http.StatusServiceUnavailable)
			_, _ = response.Write([]byte(`{"status":"not_ready"}`))
			return
		}
		ctx, cancel := context.WithTimeout(request.Context(), 8*time.Second)
		defer cancel()
		if err := check(ctx); err != nil {
			response.WriteHeader(http.StatusServiceUnavailable)
			_, _ = response.Write([]byte(`{"status":"not_ready"}`))
			return
		}
		_, _ = response.Write([]byte(`{"status":"ready"}`))
	})
	mux.Handle("GET /metrics", metrics)
	return mux
}

// RunAdmin serves one process-local health listener until ctx is cancelled.
// API and worker containers may both use :9090 because they run in separate
// network namespaces.
func RunAdmin(
	ctx context.Context,
	address string,
	check ReadyCheck,
	metrics *Metrics,
	logger *slog.Logger,
) error {
	server := &http.Server{
		Addr:              address,
		Handler:           NewAdminHandler(check, metrics),
		ReadHeaderTimeout: 5 * time.Second,
		IdleTimeout:       30 * time.Second,
		MaxHeaderBytes:    8 << 10,
	}
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("listen admin API: %w", err)
	}
	defer listener.Close()
	if logger != nil {
		logger.Info("admin server ready", "admin_address", listener.Addr().String())
	}
	failures := make(chan error, 1)
	go func() {
		if serveErr := server.Serve(listener); serveErr != nil && !errors.Is(serveErr, http.ErrServerClosed) {
			failures <- serveErr
		}
	}()
	select {
	case <-ctx.Done():
	case err = <-failures:
	}
	shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return errors.Join(err, server.Shutdown(shutdownContext))
}

// CachedChecker bounds provider readiness traffic while ensuring a stale
// success is never retained longer than TTL.
type CachedChecker struct {
	TTL   time.Duration
	Check ReadyCheck

	mu      sync.Mutex
	checked time.Time
	err     error
	running bool
	done    chan struct{}
}

func (checker *CachedChecker) CheckReady(ctx context.Context) error {
	for {
		checker.mu.Lock()
		if checker.TTL <= 0 {
			checker.TTL = 10 * time.Second
		}
		if !checker.checked.IsZero() && time.Since(checker.checked) < checker.TTL {
			err := checker.err
			checker.mu.Unlock()
			return err
		}
		if checker.Check == nil {
			checker.err = errors.New("readiness check is not configured")
			checker.checked = time.Now()
			err := checker.err
			checker.mu.Unlock()
			return err
		}
		if !checker.running {
			checker.running = true
			checker.done = make(chan struct{})
			done := checker.done
			check := checker.Check
			checker.mu.Unlock()
			go checker.finishCheck(ctx, check, done)
			select {
			case <-done:
				continue
			case <-ctx.Done():
				return ctx.Err()
			}
		} else {
			done := checker.done
			checker.mu.Unlock()
			select {
			case <-done:
				continue
			case <-ctx.Done():
				return ctx.Err()
			}
		}
	}
}

func (checker *CachedChecker) finishCheck(
	ctx context.Context,
	check ReadyCheck,
	done chan struct{},
) {
	err := check(ctx)
	checker.mu.Lock()
	checker.err = err
	checker.checked = time.Now()
	checker.running = false
	close(done)
	checker.mu.Unlock()
}

type Metrics struct {
	startedUnix  int64
	requests     atomic.Uint64
	errors       atomic.Uint64
	active       atomic.Int64
	responseTime atomic.Uint64
	workerCycles atomic.Uint64
	workerJobs   atomic.Uint64
	workerErrors atomic.Uint64
}

func NewMetrics() *Metrics {
	return &Metrics{startedUnix: time.Now().Unix()}
}

func (metrics *Metrics) Wrap(next http.Handler) http.Handler {
	return http.HandlerFunc(func(response http.ResponseWriter, request *http.Request) {
		started := time.Now()
		writer := &statusWriter{ResponseWriter: response, status: http.StatusOK}
		metrics.requests.Add(1)
		metrics.active.Add(1)
		defer func() {
			metrics.active.Add(-1)
			metrics.responseTime.Add(uint64(time.Since(started).Microseconds()))
			if writer.status >= 500 {
				metrics.errors.Add(1)
			}
		}()
		next.ServeHTTP(writer, request)
	})
}

func (metrics *Metrics) RecordWorkerCycle(processed int, err error) {
	metrics.workerCycles.Add(1)
	if processed > 0 {
		metrics.workerJobs.Add(uint64(processed))
	}
	if err != nil {
		metrics.workerErrors.Add(1)
	}
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (writer *statusWriter) WriteHeader(status int) {
	writer.status = status
	writer.ResponseWriter.WriteHeader(status)
}

// Unwrap preserves WebSocket response controller support.
func (writer *statusWriter) Unwrap() http.ResponseWriter {
	return writer.ResponseWriter
}

func (metrics *Metrics) ServeHTTP(response http.ResponseWriter, _ *http.Request) {
	response.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
	_, _ = fmt.Fprintf(response,
		"# HELP zenbo_gateway_build_info Gateway process information.\n"+
			"# TYPE zenbo_gateway_build_info gauge\n"+
			"zenbo_gateway_build_info 1\n"+
			"# HELP zenbo_gateway_start_time_seconds Process start time.\n"+
			"# TYPE zenbo_gateway_start_time_seconds gauge\n"+
			"zenbo_gateway_start_time_seconds %d\n"+
			"# HELP zenbo_gateway_http_requests_total Public HTTP requests.\n"+
			"# TYPE zenbo_gateway_http_requests_total counter\n"+
			"zenbo_gateway_http_requests_total %d\n"+
			"# HELP zenbo_gateway_http_errors_total Public HTTP 5xx responses.\n"+
			"# TYPE zenbo_gateway_http_errors_total counter\n"+
			"zenbo_gateway_http_errors_total %d\n"+
			"# HELP zenbo_gateway_http_active_requests Active public HTTP requests.\n"+
			"# TYPE zenbo_gateway_http_active_requests gauge\n"+
			"zenbo_gateway_http_active_requests %d\n"+
			"# HELP zenbo_gateway_http_response_time_seconds_total Cumulative response time.\n"+
			"# TYPE zenbo_gateway_http_response_time_seconds_total counter\n"+
			"zenbo_gateway_http_response_time_seconds_total %.6f\n"+
			"# HELP zenbo_gateway_worker_cycles_total Durable job lease cycles.\n"+
			"# TYPE zenbo_gateway_worker_cycles_total counter\n"+
			"zenbo_gateway_worker_cycles_total %d\n"+
			"# HELP zenbo_gateway_worker_jobs_total Durable jobs leased by this process.\n"+
			"# TYPE zenbo_gateway_worker_jobs_total counter\n"+
			"zenbo_gateway_worker_jobs_total %d\n"+
			"# HELP zenbo_gateway_worker_cycle_errors_total Job lease cycle failures.\n"+
			"# TYPE zenbo_gateway_worker_cycle_errors_total counter\n"+
			"zenbo_gateway_worker_cycle_errors_total %d\n",
		metrics.startedUnix,
		metrics.requests.Load(),
		metrics.errors.Load(),
		metrics.active.Load(),
		float64(metrics.responseTime.Load())/1_000_000,
		metrics.workerCycles.Load(),
		metrics.workerJobs.Load(),
		metrics.workerErrors.Load(),
	)
}
