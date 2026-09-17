# Hermes grok 原生 API 整合與架構精簡計畫

本文件保存 2026-09-16 核准的實作計畫，並納入 2026-09-17 使用者修訂：模型與既有 STT／TTS 由 Hermes grok Profile 自動管理，裝置不設定 model，Runs request 完全省略 model 欄位。實作時確認的 wire contract 以 `contracts/hermes-zenbo/`、`contracts/local-runtime/` 為準。此文件是目標與驗收清單，不是已完成測試或部署的證明。

## 1. 目標與固定設定

完整保留語音對話、五種表情、真實音量嘴型、休眠喚醒，以及原有六項裝置工具。使用既有 Hermes 管理對話與 Agent 執行，只新增一個 Hermes 內的 Zenbo 外掛，不另建 Gateway、資料庫或服務。

```text
OPENAI_BASE_URL=https://hermes.internal.c3land.org/hermes-api/p/grok/v1
OPENAI_API_KEY=由 secret/OPENAI_API_KEY 載入
```

- 所有對話請求固定保留 `grok` Profile 前綴，不退回預設 Profile；模型由 Profile 管理，不傳 model override。
- 本機測試從 secret 檔案讀取 key；裝置透過設定頁保存至 Native Keystore，不打包進 APK 或 Web assets。
- Profile 路由與 Runs 能力曾於規劃階段探測；實際 Runs、語音及六工具閉環仍須依本計畫驗收。
- Android 6／API 23 為實機基準。

## 2. 精簡後的架構

| 元件 | 責任 |
| --- | --- |
| Web Renderer | PixelFace、VAD 收音、字幕、WebAudio 播放與音量嘴型 |
| Android Native | Hermes 連線、對話協調、設定與憑證、ASUS SDK、工具安全檢查 |
| 既有 Hermes＋Zenbo 外掛 | 原生 session／run／取消；六工具轉接；重用既有 STT／TTS |

Native 直接使用 Hermes 原生 API：

- 對話：`…/hermes-api/p/grok/api/sessions`。
- 執行與取消：`…/hermes-api/p/grok/v1/runs`。
- 進度：Hermes 原生 Runs SSE；斷線後查詢 run 狀態，不沿用舊 Gateway 的 WebSocket 重播假設。
- 每台裝置同時只允許一個 active run；多輪重用同一 Hermes session。

同一個 Hermes 外掛補足裝置與音訊接口：

```text
/hermes-api/zenbo/grok/v1/device-channel
/hermes-api/zenbo/grok/v1/audio/transcriptions
/hermes-api/zenbo/grok/v1/audio/speech
/hermes-api/zenbo/grok/v1/audio/{artifact_id}
```

接口走 HTTPS／WSS 443，沿用 Hermes Profile 範圍與 API key 驗證。獨立 namespace 避開 Profile catch-all 路由掛載衝突。

Native 與 Web 保留單一 `127.0.0.1:8787` 通道，本機契約同步升為 `/api/v2`，事件由 Native 管理，不透傳遠端 Gateway envelope。兩端隨同一 APK 更新，不維護雙套協定。

## 3. 功能與失敗處理

### 語音與表情

- 保留 VAD → WAV → 外掛呼叫 Hermes STT → Hermes run → 最終回答 → Hermes TTS → Native 驗證音檔 → WebAudio。
- STT／TTS 沿用 `grok` Profile 既有且已可用的設定，不自行選擇 provider 或另部署語音服務。
- 支援 TTS 多段音檔依序播放，保留 MIME、大小與 SHA-256 驗證。
- `show_emotion` 先暫存，首段音訊真正播放時才切換及計時；取消、播畢、錯誤或休眠時清除。
- 嘴型取自實際播放音訊振幅；播畢約 500 ms 續聽，20 秒無語音休眠。
- 語音失敗時保留文字回覆、設定與重試能力，明確顯示錯誤，不偷偷換 provider。

### 六項裝置工具

外掛註冊 `get_system_status`、`start_robot_following`、`stop_robot_following`、`look_at_user`、`show_emotion`、`go_to_sleep`，沿用既有參數與 ownership。

- Native 主動建立 WSS 裝置通道；按 Profile、session、run、call ID 關聯請求。
- 外掛等待裝置結果，再回傳 Hermes 原生 tool result，不把一般工具進度事件當成待執行指令。
- 保留 Native allowlist、參數範圍、deadline、動作互斥與重複執行防護。
- 未綁定裝置、過期 run、斷線或結果不確定時回傳錯誤，不重播實體動作。

### 取消與恢復

- 點臉、頭部按鍵、關閉螢幕或休眠時，先停本地播放與動作、撤銷工具權限，再呼叫 Hermes run stop。
- SSE 關閉不代表取消完成，查詢 run 狀態直到 terminal。
- 舊 run 未結束或工具仍待回報時，不啟動同 session 的下一個 run。
- App 重啟後先核對保存的 session／run，忽略已取消回合的晚到音訊與工具。
- 本次不新增播放中免觸碰語音插話；保留點擊／頭部按鍵中斷。

## 4. 實作順序與清理

1. 確認實際 Hermes revision、外掛掛載／Profile 驗證接口，以及一輪真實 WAV 辨識與 TTS 合成。缺語音設定時先查正確 Profile 與既有整合，不覆寫使用者已有可用設定；內部接口不相容就停止部署，不擅加服務或改 Hermes core。
2. 實作 Native Hermes client，完成 grok session、run、SSE、取消、重連與設定頁。
3. 完成同一外掛的六工具裝置通道與音訊接口，接回表情、播放及機器人控制。
4. 淘汰自建 Agent Gateway 1.0 的 client、契約與 Fake Gateway，改成 Hermes API fixtures、外掛測試及本機契約測試；更新 README、AGENTS 與操作文件。

使用新的 `codex/` 分支，保留 secret 忽略規則，不併入 Go Gateway，不保留舊後端相容層。外掛只存短期裝置綁定、待回報工具及音訊暫存，不新增持久化資料庫。原始錄音辨識後刪除，TTS 暫存最長 30 分鐘。

## 5. 驗收條件

- Profile：所有請求維持 grok 路由且完全省略 model override；錯誤 key／Profile 明確失敗，不 fallback。
- 對話：多輪文字、Runs SSE、取消、斷線恢復、App 重啟；無重複回答與舊回合重播。
- 語音／畫面：短中文及長回答均可辨識播放；多段完整，表情從首段出聲開始，嘴型隨實際音量。
- 工具：六工具真實往返；錯誤參數、逾時、重複、取消後請求均正確拒絕。
- 自動測試：Web、本機契約、Hermes client、外掛、Android unit tests；完成 Web／APK build。
- 實機：指定 Zenbo K Android 6 的 TLS、麥克風、播放、HOME 恢復、休眠及動作；記錄型號、韌體，不以桌面測試替代。

完成標準：一台 Zenbo 透過指定 grok Profile 完成「收音 → 回答 → 表情與嘴型 → 裝置動作 → 中斷／續聽」，部署需求維持現有 Hermes 加一個 Zenbo 外掛。
