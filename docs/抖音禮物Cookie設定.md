# 抖音禮物 Cookie 設定

錄製時若聊天、進場、點讚正常但 XML 沒有禮物，多半是 WS 連線缺少登入 Cookie（`sessionid`）。依下列步驟在本機設定即可。

## 1. 從 Chrome 匯出 Cookie

1. 用**已登入**的 Chrome 開啟 `https://live.douyin.com/`。
2. 按 `F12` 開 DevTools → **Application** → **Cookies** → 選 `https://live.douyin.com`。
3. 至少複製 `sessionid` 的值；**建議**把頁面上相關 Cookie 整段抄下來（例如 `sessionid`、`ttwid` 等）。

## 2. 寫入本機 Cookie 檔

建立文字檔，例如：

```
%USERPROFILE%\.dyass\douyin_cookies.txt
```

內容一行即可，格式與瀏覽器請求標頭類似：

```
sessionid=你的值; ttwid=你的值
```

- 以 `#` 開頭的行會被忽略。
- 多行內容會以 `; ` 合併成一段 Cookie 字串；每行結尾的 `;` 可省略。

## 3. 告訴 stream-rec 讀哪個檔

擇一即可：

**方式 A — 全域抖音設定**

在 stream-rec 的 `douyinConfig` 裡設 `cookiesFile` 為上述檔案的**絕對路徑**，例如：

`C:\Users\你的帳號\.dyass\douyin_cookies.txt`

**方式 B — 主播下載設定**

在該主播的抖音 `downloadConfig` 裡設 `cookiesFile`。此設定會優先於全域 `douyinConfig.cookiesFile`。

**方式 C — 環境變數**

設定：

```
DOUYIN_COOKIES_FILE=C:\Users\你的帳號\.dyass\douyin_cookies.txt
```

若同時有設定，設定檔中的 `cookiesFile` 優先於環境變數。

## 4. 主播設定會覆寫檔案

某個主播的設定裡若 `cookies` 字串**有值**，會優先使用該字串，**不會**再讀 Cookie 檔。要改用檔案時，請清空該主播的 `cookies` 欄位。

優先順序：`主播 cookies` → `全域 cookies 字串` → `主播 cookiesFile` → `全域 cookiesFile` → `DOUYIN_COOKIES_FILE`。

## 5. Cookie 過期時

登入態過期後，禮物數量可能又變成 0。請重新從 Chrome 複製 Cookie、更新本機檔案，並**重啟**該場錄製（或重啟 stream-rec）。

## 6. 安全提醒

- **勿**把 Cookie 檔提交到 git。
- 本機檔名若為 `douyin_cookies.txt`，已在 `stream-rec/.gitignore` 忽略。
- Cookie 等同登入憑證，請只放在本機、勿分享或上傳。
