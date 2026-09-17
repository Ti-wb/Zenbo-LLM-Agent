# Zenbo LLM Agent — Hermes grok

Zenbo K 的 Android Launcher 與語音／像素表情介面。Android Native 連接既有 Hermes
`grok` Profile，模型、STT 與 TTS 都由 Hermes 管理；Zenbo 外掛共用既有 Hermes 程序，
不另建 Gateway 或資料庫。Web 只連本機 `127.0.0.1:8787/api/v2`，API key 由 Native Keystore 保存。

[實機驗證摘要](docs/implementation/acceptance.md) ·
[Hermes 契約](contracts/hermes-zenbo/README.md) ·
[本機介面契約](contracts/local-runtime/README.md) ·
[外掛安裝](integrations/hermes-zenbo/README.md)

## 日常操作

- 點右上 **○**、點臉或按頭部按鍵開始聆聽；聆聽中再按會休眠，回答中再按會中斷並重新聆聽。
  看到「我在聽」即可說話；就緒時有輕短提示音，四角亮度反映實際收音音量。
- **長按齒輪 2 秒**開啟設定，使用管理 PIN 解鎖。
- **動作預設關閉並記住設定**，關閉時不跟隨、不轉頭，語音與表情照常。
  USB 連線測試請保持「動作 關」；實體移動需另外在安全空間驗證。
- 回答播放時暫停收音；要中斷請點臉或按頭部按鍵。表情與嘴型跟隨回答音訊，支援依序播放多段回答。

## Hermes 連線

- Gateway URL：`https://hermes.internal.c3land.org/hermes-api/p/grok/v1`
- API key：取用 `secret/` 資料夾內既有的 Hermes key，填入 App 的 Native 設定。

首次設定填入完整 URL、Hermes key 與 6–12 位管理 PIN，先測試連線再保存。
模型不需指定；所有請求保留 `grok` Profile 路徑，不退回其他 Profile。
公開連線統一 HTTPS／WSS 443；STT、TTS 的模型、語言與聲音設定在 Hermes 管理。

更新設定時 key 留白會保留既有值。不可把 key 注入 Vite、Web storage 或 APK。
TLS 優先使用 `SYSTEM_TRUST`；使用憑證釘選時須先透過可信管道核對指紋。

## 開發與建置

需要 Node `^20.19.0 || >=22.12.0`、JDK 17、Android SDK Platforms 34／36、ADB，
以及相容的 [ASUS SDK JAR](android/ZenboSDK/README.md)。裝置基準是 **Android 6／API 23**。

```sh
npm ci
npm run dev       # Web 開發；完整功能需要 Android Local Runtime
npm run build     # Web 建置
npm run android   # 更新 Android 內嵌 Web assets
cd android
./gradlew :KiraZenbo:assembleDebug
```

`npm run android:watch` 可持續更新內嵌 Web assets。覆蓋安裝使用 `adb install -r`，
保留既有 PIN／key 與原廠 Launcher；不要用移除 App 的方式處理簽章衝突。
下載工具、SDK JAR、secrets、錄音、APK 與 QA 截圖放在 Git 忽略的位置。

依改動選擇相關測試；日常只跑受影響範圍。常用指令如下（從專案根目錄執行）：

```sh
npm test
npm run test:contracts
npm run test:hermes
cd android
./gradlew :RobotActivityLibrary:testDebugUnitTest :KiraZenbo:testDebugUnitTest
```

`test:hermes` 使用離線案例，不讀正式 key；Python 外掛測試見 [外掛文件](integrations/hermes-zenbo/README.md)。
更新 Web runtime dependency 時，執行 `npm run licenses:web` 更新授權清單。

## 快速除錯

| 現象 | 先檢查 |
| --- | --- |
| ADB 沒裝置 | `adb devices -l`、資料線、USB Debug 與裝置上的偵錯授權 |
| 未連線／驗證／TLS 錯誤 | 裝置網路及時間、完整 grok URL、key；長按齒輪測試連線 |
| 沒收音或沒回答 | 麥克風權限、是否顯示「我在聽」、Hermes STT／run／TTS 哪一步停止；字幕來自辨識或回答內容 |
| 等待前一回合結束 | 取消後須等 Hermes run 結束；先查遠端 run 狀態，不重複觸發動作 |
| 沒有跟隨／轉頭 | 先確認「動作 關」是否為預期；USB 測試期間保持關閉 |

目前已驗證流程與尚待實機測試項目統一見 [實機驗證摘要](docs/implementation/acceptance.md)。
桌面或建置成功不能代替指定 Zenbo 韌體的收音、播放與動作驗證。

## 授權

原始碼採 [Apache-2.0](LICENSE)。保留 [Third-party notices](THIRD_PARTY_NOTICES.md)、
[Web licenses](WEB_THIRD_PARTY_LICENSES.txt) 與 [Android NOTICE](android/NOTICE)。
ASUS SDK binary 不在本專案授權範圍，不得 commit 或重新散布。
