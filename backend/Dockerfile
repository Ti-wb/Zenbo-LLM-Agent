# syntax=docker/dockerfile:1.10

FROM golang:1.26.5-bookworm AS go-builder
WORKDIR /src
COPY go.mod go.sum ./
RUN --mount=type=cache,target=/go/pkg/mod go mod download
COPY . .
ARG VERSION=dev
RUN --mount=type=cache,target=/go/pkg/mod \
    --mount=type=cache,target=/root/.cache/go-build \
    CGO_ENABLED=0 go build \
      -trimpath \
      -ldflags="-s -w -X main.version=${VERSION}" \
      -o /out/zenbo-gateway ./cmd/zenbo-gateway

FROM debian:bookworm-slim AS codex-downloader
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
ARG TARGETARCH
ENV CODEX_VERSION=0.145.0
# Exact official musl release assets and digests are pinned here so a deployer
# cannot substitute a different binary through Compose or .env.
RUN set -eu; \
    case "${TARGETARCH}" in \
      amd64) \
        codex_asset="codex-x86_64-unknown-linux-musl.tar.gz"; \
        codex_binary="codex-x86_64-unknown-linux-musl"; \
        codex_sha256="bfaf13c9ba34f2ad764e4a916c49cf7177aeba329cf0f719e2227566fc8d662a" \
        ;; \
      arm64) \
        codex_asset="codex-aarch64-unknown-linux-musl.tar.gz"; \
        codex_binary="codex-aarch64-unknown-linux-musl"; \
        codex_sha256="d384f90bc842450b42bd675feef06a12a46a3b1ca97efcb22566b270e4a11227" \
        ;; \
      *) echo "unsupported TARGETARCH: ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl --fail --location --proto '=https' --tlsv1.2 \
      --output "/tmp/${codex_asset}" \
      "https://github.com/openai/codex/releases/download/rust-v${CODEX_VERSION}/${codex_asset}"; \
    printf '%s  %s\n' "${codex_sha256}" "/tmp/${codex_asset}" | sha256sum --check --strict; \
    mkdir -p /out; \
    tar -xzf "/tmp/${codex_asset}" -C /out; \
    mv "/out/${codex_binary}" /out/codex; \
    chmod 0755 /out/codex; \
    test "$(/out/codex --version)" = "codex-cli ${CODEX_VERSION}"

FROM debian:bookworm-slim AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      bubblewrap ca-certificates curl tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system --gid 10001 gateway \
    && useradd --system --uid 10001 --gid gateway \
      --home-dir /var/lib/zenbo-gateway --shell /usr/sbin/nologin gateway \
    && install -d -o gateway -g gateway -m 0750 \
      /var/lib/zenbo-gateway \
      /var/lib/zenbo-gateway/artifacts \
      /etc/zenbo-gateway \
    && install -d -o gateway -g gateway -m 0700 \
      /var/lib/zenbo-gateway/codex \
    && install -d -o root -g gateway -m 0550 /var/empty/codex
COPY --from=go-builder /out/zenbo-gateway /usr/local/bin/zenbo-gateway
COPY --from=codex-downloader /out/codex /usr/local/bin/codex
COPY profiles.example.json /etc/zenbo-gateway/profiles.json
COPY --chown=root:root --chmod=0444 codex-requirements.toml /etc/codex/requirements.toml
USER gateway:gateway
ENV GATEWAY_PROFILES_FILE=/etc/zenbo-gateway/profiles.json \
    GATEWAY_ARTIFACT_DIR=/var/lib/zenbo-gateway/artifacts \
    GATEWAY_CODEX_BINARY=/usr/local/bin/codex \
    GATEWAY_CODEX_HOME=/var/lib/zenbo-gateway/codex \
    GATEWAY_CODEX_CWD=/var/empty/codex \
    GATEWAY_HTTP_ADDR=:8080 \
    GATEWAY_ADMIN_ADDR=:9090
EXPOSE 8080 9090
ENTRYPOINT ["/usr/bin/tini", "--", "/usr/local/bin/zenbo-gateway"]
CMD ["serve"]
