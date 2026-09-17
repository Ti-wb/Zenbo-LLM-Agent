# Hermes grok 與 Zenbo 驗收

更新：2026-09-17。以下分開記錄自動測試、Hermes 服務與實機證據；服務通過不代表裝置端完整流程已完成。

## 本輪已確認

| 項目 | 結果與界線 |
| --- | --- |
| Web | `npm test`：149 項測試全數通過 |
| 表情改版 | 五種新表情、睡眠 Z／呼吸、雙眨眼及 wink；保留實際音量嘴型與 reduced motion。桌面已實際查看 Vue 六格畫面，實機結果另列 |
| 本機契約 | `npm run test:contracts`：13 個路徑、30 個有效及 31 個拒絕 fixtures 通過 |
| Hermes fixtures | `npm run test:hermes`：5 項通過，含真實匿名 run／SSE／idempotency 記錄 |
| 外掛 Python | 45 項測試通過 |
| Android 測試與建置 | 58 項測試全數通過（KiraZenbo 57、RobotActivityLibrary 1）；`assembleDebug` 成功 |
| 正式 APK | 不含臨時診斷碼；source、Git index 與 APK 掃描未檢出 API key 或 PIN |
| 實際 Hermes | 外掛已在既有 Hermes 程序內運行，公開 HTTPS／WSS 443；grok run 完全省略 `model`，由 Profile 管理模型 |
| 工具往返 | 真實 Hermes run 呼叫 `show_emotion`，由模擬裝置回報結果；尚非 Zenbo 實機六工具驗收 |
| 服務端語音 | TTS 音檔 44,496 bytes 雜湊通過；轉為 7.416 秒 WAV 後 STT 往返通過 |
| STT 語言 | 依使用者選擇，grok `stt.language` 設為空字串，自動辨識已生效；模型、provider 與 Profile 其餘設定保持原樣。實機中文回答尚未通過 |

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
| 設定保存 | 改版 APK 安裝後仍為 `onboardingComplete=true`、`hasApiKey=true`，沒有錯誤回到首次設定；`micEnabled=false`、`turnState=IDLE` |
| 真實本機 API | `/api/v2/status`、`/api/v2/conversation` 均 HTTP 200 且符合正式 v2 schema；session 與 sequence 一致 |
| 網路與時鐘 | 本輪 `network_connected=true`，裝置與電腦時鐘相差少於 5 分鐘；連網與校時通過 |
| Android 6 TLS | 已補足缺少的官方 ISRG Root X1，限 Native Hermes 驗證使用，保留 PKIX／hostname 驗證；未更動系統 CA、`SYSTEM_TRUST` 或 pins。實機 Hermes 為 `READY` |
| 靜態畫面 | 已實際查看最終版本截圖，PixelFace、時鐘與設定畫面顯示正常；原廠表情遮罩隱藏後持續數分鐘未回復 |
| 設定操作 | 最終 APK 新程序中，實際長按齒輪約 2 秒可開啟既有 PIN 解鎖畫面；PIN／key 保留，未錯誤進入首次設定 |
| 彈窗操作 | 實機 A/B 確認 ASUS 原生全螢幕網路告警會攔截觸控；正常按原廠 X 關閉後，「開啟設定」與「稍後」均正常。遇告警請關閉原廠 X 或恢復網路；未永久停用原廠告警 |
| 前景行為 | 已移除語音偵測回呼與開機接收器強制開啟 App；開機僅啟動服務。實機停留 Android Settings 60 秒，App 未搶回前景 |
| 本機休眠 | 切至 Android Wi-Fi 設定再返回 App，正式 `visibilitychange` 觸發休眠：`sleeping=true`、`micEnabled=false`、`turnState=IDLE`，Native 無 active turn 且 RobotAPI ready；睡臉持續 30 秒正常，未被原廠臉覆蓋 |
| 語音入口 | 麥克風權限已授予；實際按 ○ 進入 `READY/LISTENING`、`micEnabled=true` 且無錯誤，再按 ○ 可休眠並關閉麥克風 |
| 實際收音 | 實機音訊 frames 持續抵達，語音機率會隨說話變化，確認確實收到聲音；此前說完無回覆，尚未完成回答播放 |
| 頭部按鍵 | 已修正短按／中按辨識並通過回歸測試；實際按頭後的一句自然中文仍待最終驗收 |
| 收音回饋 | 已實作收到第一個音訊 frame 才顯示聆聽並發出一次輕提示音；背景像素框光暈隨輸入 RMS 變化。最終實機感受待驗 |
| 動態畫面／語音硬體 | 桌面造型與實際收音通過，不代表錄音提交、Hermes 播放、表情同步或音量嘴型已通過 |
| 實體動作 | 依使用者要求未測試，不執行轉頭、跟隨或其他移動 |

實機已修正兩個遮擋問題：全螢幕來電式服務通知改為低重要性的持續通知；前景啟動時透過既有 RobotAPI 的官方 `RobotFace.HIDEFACE` 隱藏原廠表情遮罩。這些操作未觸發語音或動作。靜態畫面與設定觸控通過，不代表動態表情或語音流程通過。

已修正 Native multipart 路由在音訊部分尚未讀完時誤報 MIME 的問題：等待完整 request body 後再處理，原有 `audio/wav` 與 WAV 內容嚴格驗證保留。

切出 App 的本機休眠已通過；這不代表 20 秒靜音休眠或 Hermes `go_to_sleep` 端到端已通過。

`robotReady=true` 只證明 Native 的 RobotAPI readiness，不能替代實體動作或感測器驗收。

## 本輪剩餘驗收

- [ ] 實際按頭後說一句自然中文，確認錄音完成並送入 Hermes，收到回答、播放及同步表情。

目前以這一趟自然語音閉環為準。較廣的長時間運作、多段播放、斷線／重啟與取消邊界、20 秒靜音休眠及六工具完整回歸留待另行安排；HOME 停留不再追加測試。實體移動依使用者要求未測。

完成上述語音閉環前，不把此版本標記為完整實機可用。
