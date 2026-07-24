package main

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/blob"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/config"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/providerbridge"
	gatewayruntime "github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/runtime"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/store"
	"github.com/Ti-wb/Zenbo-LLM-Agent/backend/internal/worker"
)

func runWorker(
	ctx context.Context,
	runtimeConfig config.Runtime,
	profiles config.File,
	logger *slog.Logger,
) error {
	repository, err := store.Open(ctx, runtimeConfig.DatabaseURL)
	if err != nil {
		return err
	}
	defer repository.Close()
	blobs, err := blob.NewFileStore(runtimeConfig.ArtifactDirectory)
	if err != nil {
		return err
	}
	providers, err := providerbridge.New(profiles, providerbridge.Options{
		CodexBinary:     runtimeConfig.CodexBinary,
		CodexHome:       runtimeConfig.CodexHome,
		CodexWorkingDir: runtimeConfig.CodexWorkingDir,
		ProviderTimeout: runtimeConfig.ProviderTimeout,
	})
	if err != nil {
		return err
	}
	defer providers.Close()
	startupContext, startupCancel := context.WithTimeout(ctx, 8*time.Second)
	err = providers.Ready(startupContext)
	startupCancel()
	if err != nil {
		logger.Warn("gateway worker starting not ready", "component", "providers")
	}

	metrics := gatewayruntime.NewMetrics()
	process, err := worker.New(repository, blobs, providers, worker.Config{
		WorkerID:        runtimeConfig.WorkerID,
		Logger:          logger,
		LeaseDuration:   runtimeConfig.WorkerLease,
		IdlePoll:        runtimeConfig.WorkerPoll,
		ProviderTimeout: runtimeConfig.ProviderTimeout,
		OnLeaseCycle:    metrics.RecordWorkerCycle,
	})
	if err != nil {
		return err
	}
	checker := &gatewayruntime.CachedChecker{
		TTL: 10 * time.Second,
		Check: func(checkContext context.Context) error {
			if err := repository.Ping(checkContext); err != nil {
				return fmt.Errorf("postgres: %w", err)
			}
			if err := providers.Ready(checkContext); err != nil {
				return fmt.Errorf("providers: %w", err)
			}
			return nil
		},
	}

	processContext, cancel := context.WithCancel(ctx)
	defer cancel()
	results := make(chan error, 2)
	go func() {
		results <- gatewayruntime.RunAdmin(
			processContext,
			runtimeConfig.AdminAddress,
			checker.CheckReady,
			metrics,
			logger,
		)
	}()
	go func() {
		results <- process.Run(processContext)
	}()
	first := <-results
	cancel()
	second := <-results
	return errors.Join(first, second)
}
