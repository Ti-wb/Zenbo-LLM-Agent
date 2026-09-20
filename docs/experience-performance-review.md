# 整體體驗與效能 Review

日期：2026-09-20。本輪已完成中斷／設定操作、PixelFace 重繪、Native 音訊／資產傳送，
以及 Hermes artifact／activation 等待的改善。離線測試、瀏覽器檢查與建置通過，
變更留在本機；API 23 真機體驗與端到端效能尚未驗證。

## 範圍與原則

檢查 Web 互動狀態、PixelFace、VAD／WAV、音訊播放、中斷與恢復；
Android loopback、音訊下載／驗證、靜態資產、回合生命週期；
Hermes 外掛的 speech worker、artifact、device broker，以及依賴與建置資產。

本輪先處理可用離線案例證明的互動順序、重複運算及記憶體配置問題。
維持單一 Hermes 外掛、既有協定、六種裝置工具與 Android 6／API 23 基準；
未升級依賴、部署服務或操作機器人。

## 已修正項目

| 優先級 | 發現 | 處置 |
| --- | --- | --- |
| P1 | 取消原先等待網路回覆，延後本機播放停止與使用者回饋 | 先停本機播放，再等 Native／遠端取消；維持 Native 在回合未終止時禁止新回合 |
| P2 | 設定視窗開啟後缺少完整鍵盤焦點管理 | 加入初始焦點、Tab 循環、Escape 關閉與關閉後焦點還原 |
| P2 | PixelFace 持續產生相同畫面，重複觸發計算與繪製 | 對實際輸出相同的幀去重，保留表情與音訊嘴型 |
| P2 | Native 音訊與大體積靜態資產存在額外完整緩衝／複製 | 已知長度音訊直接寫入結果陣列；HTML／VAD 資產改串流傳送 |
| P2 | Hermes TTS 音訊 metadata／SHA、artifact digest 與 broker 等待路徑有同步／重複工作 | TTS 音訊準備移至既有 worker、GET 重用 digest；activation 以事件即時喚醒，保留 interrupt 檢查 |

獨立程式碼審查已完成；審查發現的取消恢復與串流斷線邊界問題已修正並加入回歸測試。

## 可重現的改善證據

| 路徑 | 修改前 → 修改後 | 驗證方式 |
| --- | --- | --- |
| Web 中斷 | 等取消 ACK 才清理 → ACK 未回時已停止播放、暫停 VAD 並撤銷舊回合 Web 工具 | 慢 ACK／ACK 失敗、連點、移動中與非移動中；舊終止事件不得打斷已恢復的聆聽 |
| 設定視窗 | 焦點可留在背景 → 開啟聚焦 URL、Tab 雙向循環、關閉後還原焦點 | Chromium 12 項 synthetic runtime 互動檢查；已設定時 Escape 關閉並清空敏感草稿，初次設定不允許 Escape 關閉 |
| PixelFace | 中性待機 1,800 ticks 的幾何建構／繪製次數 1,800 → 103；`fillRect` 215,804 → 12,164 | 固定 30 fps、60 秒輸入；另以 47,600 組完整 frame 比對原模型一致；快取僅保留上一幀 |
| Native 資產 | 每請求讀完整檔案並複製 → AndroidAsync 串流 pump，本專案不保留整檔 buffer | injected pump／transport 驗證無預先讀檔、header 反壓、空檔案，以及完成、失敗、斷線時只關閉一次 |
| Native 音訊 | 已知長度先累積至 `ByteArrayOutputStream` 再複製 → 直接配置結果 `byte[]` | 10 MiB 邊界、過長／過短、未知長度、取消 IO 與 caller close；未知長度仍限制上限 |
| Hermes artifact | event loop 建立 SHA、每次 GET 重算 digest 並掃過 store → 既有 TTS worker 預先算好，GET 重用 digest 並只檢查目標有效期 | 測試禁止 GET 呼叫 SHA／全表清理，檢查 scope、TTL、容量原子性及取消後不發布 |
| Hermes activation | 等下一個 25 ms poll tick → activation 事件喚醒 | 保留原期限、外部 interrupt 檢查、撤銷／斷線與不重播測試 |

測試來源：[Web 控制器](../src/composables/useRuntimeController.test.js)、
[PixelFace](../src/components/pixelFaceModel.test.js)、
[Native 資產](../android/KiraZenbo/src/test/java/com/robot/asus/kira/AppAssetStreamTest.java)、
[Native 音訊](../android/KiraZenbo/src/test/java/com/robot/asus/kira/HermesBodyReadTest.java)、
[Hermes audio](../integrations/hermes-zenbo/tests/test_audio.py) 與
[broker](../integrations/hermes-zenbo/tests/test_broker.py)。
Hermes 另提供[本機 synthetic microbenchmark](../integrations/hermes-zenbo/tests/perf_probe.py)；
它分別量測 publication、GET digest 與 activation race，不含 worker 準備、網路、
provider 或硬體耗時，也不代表每回合固定省下 25 ms。

## 驗證基線與結果

| 項目 | 修改前 | 修改後 |
| --- | --- | --- |
| `npm test` | 14 個檔案、161 tests 通過 | 14 個檔案、172 tests 通過 |
| `npm run test:contracts` | 37 valid／45 invalid 通過 | 37 valid／45 invalid 通過 |
| `npm run test:hermes` | 5 tests 通過 | 5 tests 通過，0 skipped |
| `npm run build` HTML | 576.84 kB；gzip 170.57 kB | 578.87 kB；gzip 171.30 kB；`npm run android` 通過 |
| VAD 主要公開資產 | WASM 11,905,541 B + model 2,327,524 B，合計約 14.23 MB，另有少量 JS | 不變 |
| Python 外掛測試 | 45 tests 通過 | 54 tests 通過，0 skipped |
| Android JVM／APK | 建置環境已確認 | RobotActivityLibrary 1 ＋ KiraZenbo 95，共 96 tests 通過，0 failures／errors／skipped；`assembleDebug` 產生 universal 與 4 種 ABI APK，未安裝 |
| Chromium 互動／畫面 | 修改前畫面作為比對 | 設定 12 項與嘴型／睡眠／喚醒 3 項通過、320 px 無橫向溢出；五種表情＋睡眠的靜態 canvas PNG 相同 |

本輪採可重現的呼叫順序、配置上界與重複操作次數作為改善證據；
測試執行時間和建置大小不能代表真實對話延遲、CPU、FPS 或耗電改善。
Native stream 測試使用 injected pump，實際 Android socket／GeckoView 的 chunked 傳送仍需實機驗證。
HTML 增加 2.03 kB（gzip 增加 0.73 kB）；本輪改善執行時重複工作與傳送配置，未減少 VAD 下載量。

建置環境為 JDK 17.0.20.1、Android SDK 34／36 與本機 ASUS SDK JAR。
測試指令見[開發與建置](../README.md#開發與建置)及[Python 外掛測試](../integrations/hermes-zenbo/README.md#tests)。

本機畫面證據：[主畫面](../.local/review-performance/output/playwright/app-final-1280.png)、
[設定焦點](../.local/review-performance/output/playwright/settings-final-focus-1280.png)。
圖片及原始測試資料位於 Git 忽略的 `.local/review-performance/`；預覽使用 synthetic runtime，未連接真實裝置。

## 下一步與實機驗收

下一輪先量測 VAD 冷啟動、Hermes 設定／run status 讀取與各段語音延遲，
再決定是否改資產、模型或同步路徑；這些目前尚未證實為主要瓶頸。

本次 `adb devices -l` 沒有裝置，因此沒有實測型號、韌體、API 或 ABI。
專案目標為 Zenbo K／Android 6／API 23，不能把目標規格當成本次裝置證據。

接上裝置後，先記錄型號、韌體、API、ABI；保留 App 資料及原廠 Launcher，
在動作關閉下驗證：

- API 23 上的 TLS、GeckoView、權限、VAD 冷啟動與再次聆聽。
- 收音至首段回答的延遲分解，以及長回答的記憶體、播放順序和嘴型。
- 播放中點擊中斷、離線／慢回覆、取消失敗與恢復；舊音訊不得復播。
- 真實 Hermes STT／TTS、SSE 中斷恢復與重新綁定。

動作需另在安全空間驗證跟隨、轉頭、停用與取消；離線測試不構成硬體成功。
播放期間 VAD 仍會暫停，本輪不宣稱免手動語音打斷。
