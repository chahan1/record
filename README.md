# 立即錄音機（record）

Android 錄音 App（Kotlin）。目前版本 0.3。

## 已有功能
- 開啟 App 後自動開始錄音（可在畫面上開關）
- 麥克風錄音，輸出 AAC（.m4a）
- 前景服務：螢幕關閉、切到別的 App 仍持續錄音，通知列可暫停／停止
- 錄音清單：App 內播放（可拖曳進度）、重新命名、分享、刪除
- 錄音來源：麥克風／其他 App 的聲音（Android 10+）／兩者混合

## 之後階段
- MP3 格式、分段錄音
- Google Drive 備份（Apps Script 中繼站）與完整性檢查

## 編譯
推上 GitHub 後，Actions 會自動編譯，APK 放在 Releases 的 `latest`。
簽署金鑰 `app/record.jks` 固定不變，所以新版 APK 可直接覆蓋安裝。

## 手機設定（小米 HyperOS）
安裝後到 設定 → 應用程式 → 立即錄音機：
1. 省電策略 → 無限制
2. 自動啟動 → 開啟
3. 通知 → 允許
