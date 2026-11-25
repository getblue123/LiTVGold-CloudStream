# LiTVGold-CloudStream
金光布袋戲直播與 VOD 的 CloudStream v0.2 實驗版。尚未驗證編譯或實際播放；只請求 LiTV 免登入端點，尊重伺服器拒絕、區域與 DRM，不繞過限制。第三方使用須遵守 LiTV 條款。
## 測試
在 GitHub Actions 下載成功的 LiTVGold-cs3 artifact，解壓縮後將 .cs3 匯入 CloudStream（若該版本支援本機匯入）。私人專案不公開來源庫。測試直播與仙古狂濤，記錄錯誤訊息，不分享播放權杖。
## 編譯
JDK 17、Android SDK 35、Gradle 8.14.3；gradle :LiTVGold:make。依賴快照尚未鎖定版本。
參考 https://github.com/recloudstream/extensions 及 https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/litv.py
