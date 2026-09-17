# Hermes grok 與 Zenbo 驗收

更新：2026-09-17。以下分開記錄自動測試、Hermes 服務與實機證據；服務通過不代表裝置端完整流程已完成。

## 本輪已確認

| 項目 | 結果與界線 |
| --- | --- |
| Web | `npm test`：12 個檔案、129 項測試通過 |
| 本機契約 | `npm run test:contracts`：13 個路徑、30 個有效及 31 個拒絕 fixtures 通過 |
| Hermes fixtures | `npm run test:hermes`：5 項通過，含真實匿名 run／SSE／idempotency 記錄 |
| 外掛 Python | 45 項測試通過 |
| Android 測試與建置 | 最終重建結果待補；不沿用先前測試數字作為本輪結果 |
| 實際 Hermes | 外掛已在既有 Hermes 程序內運行，公開 HTTPS／WSS 443；grok run 完全省略 `model`，由 Profile 管理模型 |
| 工具往返 | 真實 Hermes run 呼叫 `show_emotion`，由模擬裝置回報結果；尚非 Zenbo 實機六工具驗收 |
| 服務端語音 | TTS 取得 44,496 bytes、7.416 秒 WAV，雜湊核對通過；STT 傳輸與服務回應通過 |
| 中文辨識 | 尚未通過。Hermes 現有 `stt.language=en`，中文辨識不正確；等待使用者決定是否調整既有設定 |

服務端測試使用指定 grok Profile，沒有新增模型或語音 provider。API key、原始對話、錄音、APK 與完整 log 不納入 Git。

## 指定實機

| 資料 | 觀測值 |
| --- | --- |
| 裝置 | ASUS Zenbo K |
| Android | 6.0.1／API 23 |
| ABI | x86_64 |
| 韌體 | 13.10.8.240-20230807 |
| 安裝 | 原先沒有本 App 套件；本次完成首次安裝與設定 |
| Native 狀態 | `runtimeReady=true`、`robotReady=true`、`setupRequired=false` |
| 真實本機 API | `/api/v2/status`、`/api/v2/conversation` 均 HTTP 200 且符合正式 v2 schema；session 與 sequence 一致 |
| 網路與時鐘 | 裝置目前沒有網路，Gateway 為 `OFFLINE`；裝置時鐘仍在 2024 年，待網路恢復後校時並重測 TLS |
| 畫面／語音硬體 | 畫面仍黑，實體麥克風、喇叭及表情顯示尚待驗收 |
| 實體動作 | 依使用者要求未測試，不執行轉頭、跟隨或其他移動 |

`robotReady=true` 只證明 Native 的 RobotAPI readiness，不能替代實體動作或感測器驗收。

## 接續驗收

- [ ] 解決畫面顯示，實際查看 PixelFace、字幕與設定面板，保存實機截圖。
- [ ] 裝置連網、校時後重測 Android 6 TLS、grok Profile 連線、設定保存與 HOME 恢復。
- [ ] 確認中文 STT 設定選擇後，實測麥克風 → 中文辨識 → Hermes 回答 → 喇叭播放。
- [ ] 驗證五種表情在首段真正出聲才生效、嘴型跟隨音量、多段播放依序完成；取消與錯誤會清除表情及餘下音訊。
- [ ] 驗證點臉、頭部按鍵、screen off、sleep、中斷後續聽與 20 秒無語音休眠。
- [ ] 驗證 SSE 中斷、App 重啟及取消後 `TURN_BUSY`；晚到工具／音訊不得套用到新回合。
- [ ] 在本次不執行實體動作的限制下，驗證狀態查詢、Web 表情與休眠工具的實機往返。

裝置網路、畫面及語音硬體完成上述驗收前，不把此版本標記為完整實機可用。實體動作留待另行授權與測試。
