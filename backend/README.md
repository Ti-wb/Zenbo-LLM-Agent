# Zenbo AI Gateway（Go）

這個目錄是獨立部署的 Agent Gateway 1.0。Android Native
`AgentGatewayClient` 只透過 HTTPS/WSS 連到它；Vue Renderer 仍只連
`127.0.0.1:8787` 的 Local Runtime，不會取得 provider credential，也不會直接
呼叫 LLM、STT 或 TTS。

## 已實作的邊界

- 單一 `zenbo-gateway` binary：`serve`、`worker`、`migrate`、
  `device issue|revoke`、`codex login|status|logout`、`config check`。
- `/agent/v1` 的九條 HTTP route 與 session WebSocket event stream。
- PostgreSQL durable state、transactional sequence、`LISTEN/NOTIFY` replay、
  idempotency、job lease、per-session advisory lock、artifact metadata。
- 共享檔案 artifact store：原子寫入、長度與 SHA-256；原始輸入音訊在
  STT 後即排入刪除流程。
- OpenAI-compatible Responses、Chat Completions、transcriptions、speech；
  可直接接 LiteLLM。
- Codex app-server JSONL supervisor與 stable API，固定 `codex-cli 0.145.0`，
  OAuth token 只由專用 `CODEX_HOME` 保存。
- `:8080` public API，以及每個 API/worker container 自己的 `:9090`
  `/healthz`、`/readyz`、`/metrics`。

Normative contracts 仍在 repository root 的
`contracts/agent-gateway/`。build 內嵌一份 reviewed copy，測試會以 SHA-256
和逐 byte 比對阻擋 contract drift：

```sh
./scripts/check-contract-drift.sh
```

## 本機準備

需要 Go 1.26.x、PostgreSQL、可寫的 artifact 目錄，以及 profiles 指定的
provider。先建立設定：

```sh
cd backend
cp profiles.example.json profiles.json
cp .env.example .env
```

產生 secret（不要 commit `.env` 或 `profiles.json`）：

```sh
openssl rand -base64 32
openssl rand -base64 32
```

第一個值可作 PostgreSQL password，第二個放成
`GATEWAY_DEVICE_HMAC_KEY=base64:<value>`。Provider key 的**值**只放環境變數；
`profiles.json` 只能用 `apiKeyEnv` 指向環境變數名稱。若是明確不驗證的本機
相容服務，才設 `allowUnauthenticated: true`，並省略 `apiKeyEnv`。

必要環境：

```sh
export GATEWAY_DATABASE_URL='postgresql://gateway:...@127.0.0.1:5432/gateway?sslmode=disable'
export GATEWAY_DEVICE_HMAC_KEY='base64:...'
export GATEWAY_PROFILES_FILE="$PWD/profiles.json"
export GATEWAY_ARTIFACT_DIR="$PWD/.data/artifacts"
export GATEWAY_CODEX_BINARY=/absolute/path/to/codex
export GATEWAY_CODEX_HOME="$PWD/.data/codex"
export GATEWAY_CODEX_CWD="$PWD/.data/codex-empty"
export LITELLM_API_KEY='...'
mkdir -p "$GATEWAY_CODEX_HOME" "$GATEWAY_CODEX_CWD"
chmod 0700 "$GATEWAY_CODEX_HOME"
chmod 0550 "$GATEWAY_CODEX_CWD"
```

`GATEWAY_CODEX_HOME` 必須是 absolute、已存在、owner 可讀寫搜尋且 group/other
完全無權限的目錄。`GATEWAY_CODEX_CWD` 必須是 absolute、空白、不可寫的
目錄；這是 Codex read-only sandbox 以外的第二層保護。Gateway 啟動前也會
執行 `codex --version`，只接受精確的 `codex-cli 0.145.0`。

非 Docker host 還必須把 repository 內的 managed policy 安裝到 Codex 的
system requirements 位置；不能只複製到 `CODEX_HOME`。Linux：

```sh
sudo install -d -m 0755 /etc/codex
sudo install -o root -g root -m 0444 codex-requirements.toml /etc/codex/requirements.toml
```

macOS：

```sh
sudo install -d -m 0755 "/Library/Application Support/OpenAI/Codex"
sudo install -o root -g wheel -m 0444 codex-requirements.toml \
  "/Library/Application Support/OpenAI/Codex/requirements.toml"
```

Docker image 已內建同一份 policy。每次 app-server 初始化都會透過
`configRequirements/read` 驗證 remote control 被強制關閉、approval 只能
`never`、sandbox 只能 `read-only`、Web search 關閉、hooks 只能使用 managed
來源，且 shell、apps、plugins、MCP 相關能力與其他 agentic features 均被
managed feature pins 關閉；缺少或不符時 process/readiness 直接失敗。

驗證與啟動：

```sh
go run ./cmd/zenbo-gateway config check
go run ./cmd/zenbo-gateway migrate
go run ./cmd/zenbo-gateway device issue --label zenbo-lab
go run ./cmd/zenbo-gateway serve
go run ./cmd/zenbo-gateway worker
```

`device issue` 是唯一會顯示 plaintext device token 的操作；Gateway DB 只存
HMAC-SHA256。首次 authenticated request 會原子綁定
`X-Zenbo-Device-Id`，之後不同 device ID 會被拒絕。

## profiles.json

每個 advertised profile 必須完整配置 LLM、STT、TTS。必須剛好有一個：

```json
{"id": "default", "isDefault": true}
```

`default` 是既有 Android client 使用的穩定 alias。建立 session 時會把當時
的 `providerKind` 和 profile ID 持久化；turn 執行中不 fallback，也不因為
後續修改設定而切換 provider。若同 ID 的 `kind` 改變，既有 session 會明確
失敗，而不是靜默換 provider。

OpenAI-compatible LLM 的 `mode` 必須明確選：

- `responses`：LiteLLM 預設建議。
- `chat_completions`：需要舊式 chat endpoint 的相容服務。

Codex profile 的 media 仍是必要欄位；Codex OAuth 只提供 LLM，沒有 STT/TTS。
所有 advertised profile 的 credential presence 與 bounded `/models` catalog
都必須實際包含所設定的 LLM、STT、TTS model；Codex profile 另以 paginated
`model/list` 驗證 LLM model（範例固定 `gpt-5.6-terra`）。全部通過時
readiness 才回 200。

## Codex OAuth

在 worker 使用的同一個 persistent `CODEX_HOME` 執行：

```sh
go run ./cmd/zenbo-gateway codex login --profile default
go run ./cmd/zenbo-gateway codex status --profile default
go run ./cmd/zenbo-gateway codex logout --profile default
```

`login` 會顯示 verification URL 和 user code，接著等待
`account/login/completed`。Access/refresh token 不會進 PostgreSQL、log 或
command output。

v1 Compose 是單一 API、單一 worker。API 不持有 provider execution client；
它只以短生命 app-server 做 Codex account readiness。真正 LLM turn 和
`turn/interrupt` 都由 worker 擁有，避免另一個 process 嘗試 interrupt
不屬於它的 active turn。Operator OAuth command、API readiness與 worker共用
`CODEX_HOME`，但只有 worker長期持有 app-server；不要在 v1 水平擴展 Codex
worker。同一 worker 即使有多個 Codex profile，也只共用一個 supervisor /
app-server；每個 turn 仍從 pinned profile request 帶入其 model。

## Docker Compose 與 TLS

Docker build 固定：

- Go toolchain `1.26.5`
- Codex `0.145.0`
- PostgreSQL `18.4-bookworm`
- Caddy `2.11.4-alpine`

Dockerfile 固定使用官方 Codex 0.145.0 musl release asset，且依
`TARGETARCH` 固定驗證 release SHA-256：amd64 使用
`codex-x86_64-unknown-linux-musl.tar.gz`，arm64 使用
`codex-aarch64-unknown-linux-musl.tar.gz`。版本、asset 與 digest 都不能由
Compose 或 `.env` 覆寫；升級時必須在同一 change 更新 static pin test。

```sh
docker compose config --quiet
docker compose run --rm api codex login --profile default
docker compose up --build -d
docker compose ps
```

Caddy 只 proxy public `:8080`，自動處理 TLS/WSS；`:9090` 沒有 publish 到
host。`artifacts` 和 `codex-home` 是共享 persistent volume，PostgreSQL 是
唯一 durable state truth。

## Health、readiness 與 observability

- `/healthz`：process liveness，不接觸 provider。
- `/readyz`：PostgreSQL與**所有 advertised profiles**；PostgreSQL probe
  由固定 `sqlc v1.31.1` 產生並由 runtime 實際呼叫。失敗回通用 JSON，
  不洩漏 DSN、token 或 transcript。
- `/metrics`：Prometheus text exposition。

JSON log 不記錄 Authorization、provider key、原始音訊或完整 transcript。
Caddy access log也沒有設定 request header logging。

## 測試

```sh
make sqlc-check
go test ./...
go test -race ./...
./scripts/check-contract-drift.sh

cd ..
npm run test:contracts
npm run test:gateway
npm run test:gateway:blackbox
```

修改 `queries/` 或 migration 後先執行 `make sqlc-generate`；生成結果提交在
`internal/store/sqlcgen/`。`make sqlc-check` 使用同一個固定版本比較 query
與生成檔，避免開發機上的全域 sqlc 版本造成 drift。

GitHub Actions 的 `Backend CI` 會以固定 Go 1.26.5、一次性的 PostgreSQL
18.4 service 實跑 unit、race、vet、build 與
`GATEWAY_TEST_DATABASE_URL` integration suite，並檢查 sqlc 與 embedded
contract drift。另一個無 credential job 會執行 locked `npm ci`、contract、
Fake Gateway 及 bundled black-box tests。CI 不讀 provider secret，也不取代
下述 credentialed smoke test 或 Zenbo K 硬體驗收。

有可用 credential 時，release 前另跑三條 smoke：

1. Codex OAuth profile：文字 turn、一次 tool call、TTS。
2. LiteLLM Responses profile：同一套流程。
3. LiteLLM Chat Completions profile：同一套流程。

Zenbo K 硬體驗收還要逐項測 session create、文字、WAV、六個 allowlisted
tools、playback update、barge-in cancel、WS cursor resume、app restart。桌面
test、race test 或 APK build 都不能替代硬體相容性驗證。

## Retention 預設

- session TTL：24 小時
- terminal turn/session 的完整 retained replay event history：24 小時；
  為維持 sequence 連續性會整段裁切，不只刪 terminal event；active-turn
  history 不提前裁切
- turn 文字 transcript 欄位：7 天
- Codex thread：terminal session 滿 7 天後，以 durable lease 呼叫
  `thread/delete`；crash 或 ownership conflict 會重試
- Codex orphan thread：worker 在任何 `turn/start` 前先持久化
  `EnsureThread` 的 ID；另以 `sourceKinds=["appServer"]`、exact dedicated cwd、
  oldest-first bounded cursor page 掃描，滿 10 分鐘 grace 且 PostgreSQL
  不認得的 thread 才刪除
- raw audio：STT 完成即刪除
- TTS artifact：30 分鐘
- terminal idempotency/jobs：24 小時

部署需備份 PostgreSQL；不要備份 raw input artifact。artifact sweeper 採 lease
後刪除，process crash 後可安全重試。

若更名或移除舊 Codex profile，live session 仍維持 strict profile pinning。
在最後一筆 Codex retention/orphan cleanup 完成前，部署設定至少要保留一個
指向同一 `CODEX_HOME` identity 的 Codex cleanup/tombstone profile；v1 完全
移除所有 Codex profile 時不會啟動 Codex cleanup client。
