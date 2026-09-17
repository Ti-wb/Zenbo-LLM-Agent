# Hermes grok 與 Zenbo 驗收

更新：2026-09-17。以下分開記錄自動測試、Hermes 服務與實機證據；服務通過不代表裝置端完整流程已完成。

## 本輪已確認

| 項目 | 結果與界線 |
| --- | --- |
| Web | `npm test`：12 個檔案、130 項測試通過 |
| 本機契約 | `npm run test:contracts`：13 個路徑、30 個有效及 31 個拒絕 fixtures 通過 |
| Hermes fixtures | `npm run test:hermes`：5 項通過，含真實匿名 run／SSE／idempotency 記錄 |
| 外掛 Python | 45 項測試通過 |
| Android 測試與建置 | KiraZenbo 52 項、RobotActivityLibrary 1 項，共 53 項測試通過，無失敗或略過；`assembleDebug` 通過 |
| 實際 Hermes | 外掛已在既有 Hermes 程序內運行，公開 HTTPS／WSS 443；grok run 完全省略 `model`，由 Profile 管理模型 |
| 工具往返 | 真實 Hermes run 呼叫 `show_emotion`，由模擬裝置回報結果；尚非 Zenbo 實機六工具驗收 |
| 服務端語音 | TTS 音檔 44,496 bytes 雜湊通過；轉為 7.416 秒 WAV 後 STT 往返通過 |
| 中文辨識 | 尚未通過。Hermes 預設將 STT 語言解析為 `en`，中文辨識不正確；等待使用者決定是否調整既有設定 |

服務端測試使用指定 grok Profile，沒有新增模型或語音 provider。API key、原始對話、錄音、APK 與完整 log 不納入 Git。

## 指定實機

| 資料 | 觀測值 |
| --- | --- |
| 裝置 | ASUS Zenbo K |
| Android | 6.0.1／API 23 |
| ABI | x86_64 |
| 韌體 | 13.10.8.240-20230807 |
| 安裝 | 原先沒有本 App 套件；已完成設定及最終版本覆蓋安裝，暫時設定入口已移除 |
| Native 狀態 | `runtimeReady=true`、`robotReady=true`、`setupRequired=false` |
| 設定保存 | 最終 APK 完整重啟後，`onboardingComplete=true`、`hasApiKey=true`、`settingsOpen=false`；沒有錯誤回到首次設定 |
| 真實本機 API | `/api/v2/status`、`/api/v2/conversation` 均 HTTP 200 且符合正式 v2 schema；session 與 sequence 一致 |
| 網路與時鐘 | 裝置目前沒有網路，Gateway 為 `OFFLINE`、`micEnabled=false`；裝置時鐘仍在 2024 年，待網路恢復後校時並重測 TLS |
| 靜態畫面 | 已實際查看最終版本截圖，PixelFace、時鐘與設定畫面顯示正常；原廠表情遮罩隱藏後持續數分鐘未回復 |
| 設定操作 | 實際觸控長按齒輪可開啟既有 PIN 解鎖畫面，顯示 API key 留白保留已存值；未錯誤進入首次設定 |
| 本機休眠 | 切至 Android Wi-Fi 設定再返回 App，正式 `visibilitychange` 觸發休眠：`sleeping=true`、`micEnabled=false`、`turnState=IDLE`，Native 無 active turn 且 RobotAPI ready；睡臉持續 30 秒正常，未被原廠臉覆蓋 |
| 動態畫面／語音硬體 | 動態表情、音量嘴型、實體麥克風及喇叭尚待驗收 |
| 實體動作 | 依使用者要求未測試，不執行轉頭、跟隨或其他移動 |

實機已修正兩個遮擋問題：全螢幕來電式服務通知改為低重要性的持續通知；前景啟動時透過既有 RobotAPI 的官方 `RobotFace.HIDEFACE` 隱藏原廠表情遮罩。這些操作未觸發語音或動作。靜態畫面與設定觸控通過，不代表動態表情或語音流程通過。

切出 App 的本機休眠已通過；這不代表 20 秒靜音休眠或 Hermes `go_to_sleep` 端到端已通過。

`robotReady=true` 只證明 Native 的 RobotAPI readiness，不能替代實體動作或感測器驗收。

## 接續驗收

- [ ] 裝置連網、校時後重測 Android 6 TLS、grok Profile 連線與 HOME 恢復。
- [ ] 確認中文 STT 設定選擇後，實測麥克風 → 中文辨識 → Hermes 回答 → 喇叭播放。
- [ ] 驗證五種表情在首段真正出聲才生效、嘴型跟隨音量、多段播放依序完成；取消與錯誤會清除表情及餘下音訊。
- [ ] 驗證點臉、頭部按鍵、screen off、sleep、中斷後續聽與 20 秒無語音休眠。
- [ ] 驗證 SSE 中斷、App 重啟及取消後 `TURN_BUSY`；晚到工具／音訊不得套用到新回合。
- [ ] 在本次不執行實體動作的限制下，驗證狀態查詢、Web 表情與休眠工具的實機往返。

裝置網路、動態互動及語音硬體完成上述驗收前，不把此版本標記為完整實機可用。實體動作留待另行授權與測試。
