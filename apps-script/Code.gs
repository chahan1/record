// 立即錄音機：Google Drive 備份中繼站
// 作用：確認金鑰正確後，回傳一個短效的 Drive 存取權杖（範圍只有 drive.file，
// 也就是只能存取這個 Apps Script 專案建立的檔案）。
// 手機拿到權杖後直接上傳到你的 Drive，權杖過期時會自動再來這裡索取。

// ★ 把下面這行改成你自己的密碼（英文數字，越長越好），並把同一個密碼填進 App
const KEY = 'laes23632077';

function doGet(e) {
  const given = (e && e.parameter && e.parameter.key) || '';
  if (given !== KEY) {
    return json_({ ok: false });
  }
  return json_({ ok: true, token: ScriptApp.getOAuthToken() });
}

// 第一次部署前，在編輯器選這個函式按「執行」，用來授權
function authorize() {
  ScriptApp.getOAuthToken();
}

function json_(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj))
    .setMimeType(ContentService.MimeType.JSON);
}
