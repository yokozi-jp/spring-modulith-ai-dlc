// ZAP httpsender script（ADR-056）。
// __Host- の Cookie は Secure なので、ZAP は HTTP の要求に自分では付けない（ADR-066、ADR-119）。
// 最後に受け取った Set-Cookie の値を global var に保存し、session/app-session.js がセッションの Cookie を付ける。
// Spring Security の csrf.spa() は __Host-XSRF-TOKEN の値を X-XSRF-TOKEN Header で送り返させる。
// ZAP の anti-CSRF 処理はフォームのトークンしか扱わないため、unsafe メソッドの要求にこの Header と Cookie を付ける。
var ScriptVars = Java.type("org.zaproxy.zap.extension.script.ScriptVars");
var TARGET_HOST = "localhost";
var TARGET_PORT = 4173;
var SAFE_METHODS = ["GET", "HEAD", "TRACE", "OPTIONS"];
var STORED = { "__Host-XSRF-TOKEN": "xsrf-token", "__Host-APP_SESSION": "app-session" };

function isTarget(uri) {
  return String(uri.getHost()) === TARGET_HOST && uri.getPort() === TARGET_PORT;
}

function sendingRequest(msg, initiator, helper) {
  var header = msg.getRequestHeader();
  if (SAFE_METHODS.indexOf(String(header.getMethod())) >= 0) return;
  if (!isTarget(header.getURI())) return;
  var cookie = header.getHeader("Cookie");
  var fromCookie = cookie ? /(?:^|;\s*)__Host-XSRF-TOKEN=([^;]*)/.exec(String(cookie)) : null;
  var token = fromCookie ? fromCookie[1] : ScriptVars.getGlobalVar(STORED["__Host-XSRF-TOKEN"]);
  if (!token) return;
  header.setHeader("X-XSRF-TOKEN", token);
  if (!fromCookie) header.setHeader("Cookie", (cookie ? cookie + "; " : "") + "__Host-XSRF-TOKEN=" + token);
}

function responseReceived(msg, initiator, helper) {
  if (!isTarget(msg.getRequestHeader().getURI())) return;
  var values = msg.getResponseHeader().getHeaderValues("Set-Cookie");
  for (var i = 0; i < values.size(); i++) {
    var match = /^(__Host-XSRF-TOKEN|__Host-APP_SESSION)=([^;]*)/.exec(String(values.get(i)));
    // 空の値は Cookie の削除なので、保存した値も消す。
    if (match) ScriptVars.setGlobalVar(STORED[match[1]], match[2] || null);
  }
}
