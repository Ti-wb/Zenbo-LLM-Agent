# Zenbo LLM Agent

讓 ASUS Zenbo K 透過 Hermes 進行語音對話，搭配像素表情、收音回饋與音訊嘴型。
Android App 連接你設定的 Hermes Profile，由 Hermes 管理對話、語音辨識（STT）與
語音合成（TTS）；隨附的 Zenbo 外掛提供裝置工具與語音介面，共用 Hermes 程序。

[外掛安裝](integrations/hermes-zenbo/README.md) ·
[Hermes 介面契約](contracts/hermes-zenbo/README.md) ·
[本機介面契約](contracts/local-runtime/README.md)

## 開始使用

需要相容的 Zenbo K（**Android 6／API 23**）及可透過 HTTPS 存取的 Hermes API。
先在 Hermes 建立要使用的 Profile、設定對話與 STT／TTS，依[外掛文件](integrations/hermes-zenbo/README.md)
安裝並啟用 `zenbo` 外掛與工具集。HTTPS 入口須支援 WebSocket 與 SSE 串流。

建置並安裝 App 後，在首次設定填入：

| 欄位 | 填寫方式 |
| --- | --- |
| Hermes Profile URL | 例如 `https://hermes.example.com/hermes-api/p/robot/v1`，請換成自己的主機與 Profile |
| API key | 該 Hermes Profile 的存取金鑰 |
| 管理 PIN | 自訂 6–12 位數字，供之後修改連線設定使用 |

URL 必須以 `/p/{profile}/v1` 結尾；範例中的 `/hermes-api` 是可選的反向代理前綴，
請依自己的部署保留或省略。App 會保留完整前綴與指定 Profile，模型與語音語言、聲音設定
均由 Hermes 管理，無須在 App 選擇模型。先測試連線，再儲存設定。

API key 由 Android Keystore 保護，更新設定時留白會保留既有值。
TLS 預設使用 `SYSTEM_TRUST`；使用憑證釘選時，須先透過可信管道核對指紋。
Web 介面只連裝置本機 `127.0.0.1:8787/api/v2`，不直接連 Hermes；金鑰不可放入 Web assets 或 APK。

## 日常操作

- 點臉或右上 **○** 開始聆聽；看到「我在聽」並聽到短提示音後即可說話。
  四角亮度反映收音音量，回答時表情與嘴型配合音訊。
- 聆聽中再點會休眠；回答中再點會中斷並重新聆聽。播放時暫停收音。
  頭部按鍵也可觸發相同操作，支援情況需依裝置韌體確認。
- **長按齒輪 2 秒**開啟設定，使用管理 PIN 解鎖。
- 頂端顯示電量與充電狀態；連線就緒且沒有進行中的回合時，按 **新對話**（New Session）開始新對話，保留連線設定與遠端舊對話。
- **動作預設關閉並記住設定**，關閉時不跟隨、不轉頭，語音與表情照常。
  USB 連線測試請保持「動作 關」；啟用動作前，需在安全空間另行確認裝置相容性。

## 開發與建置

需要 Node `^20.19.0 || >=22.12.0`、JDK 17、Android SDK Platforms 34／36、ADB，
以及自行取得的相容 [ASUS SDK JAR](android/ZenboSDK/README.md)。

```sh
npm ci
npm run dev       # Web 開發；完整功能需要 Android Local Runtime
npm run build     # Web 建置
npm run android   # 更新 Android 內嵌 Web assets
cd android
./gradlew :KiraZenbo:assembleDebug
adb shell getprop ro.product.cpu.abilist
adb install -r KiraZenbo/build/outputs/apk/debug/KiraZenbo-x86_64-debug.apk
```

安裝指令以 `x86_64` 為例，請依裝置回報的 ABI 選擇對應 APK。
`npm run android:watch` 可持續更新內嵌 Web assets。使用相同簽章覆蓋安裝可保留既有設定；
請保留原廠 Launcher。下載工具、SDK JAR、金鑰與測試產物須放在 Git 忽略的位置。

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
| 未連線／驗證／TLS 錯誤 | 裝置網路及時間、完整 Profile URL 與 key；長按齒輪測試連線 |
| Hermes API 重啟 | App 會自動重新連線，無須重填設定；等顯示已連線後，可重新說出中斷的內容 |
| 沒收音或沒回答 | 麥克風權限、是否顯示「我在聽」、Hermes STT／run／TTS 哪一步停止；字幕來自辨識或回答內容 |
| 等待前一回合結束 | 取消後須等 Hermes run 結束；先查遠端 run 狀態，不重複觸發動作 |
| 沒有跟隨／轉頭 | 先確認「動作 關」是否為預期；USB 測試期間保持關閉 |

離線測試與建置成功不能代替實機相容性驗證。首次使用自己的 Hermes 與 Zenbo 時，
先在動作關閉下確認連線、收音、回答播放與中斷，再依需求測試動作和斷線恢復。

## 授權

原始碼採 [Apache-2.0](LICENSE)。保留 [Third-party notices](THIRD_PARTY_NOTICES.md)、
[Web licenses](WEB_THIRD_PARTY_LICENSES.txt) 與 [Android NOTICE](android/NOTICE)。
ASUS SDK binary 不在本專案授權範圍，不得 commit 或重新散布。
