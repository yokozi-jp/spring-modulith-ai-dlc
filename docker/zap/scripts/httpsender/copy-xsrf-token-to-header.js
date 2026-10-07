// ZAP httpsender script（ADR-056）。
// Spring Security の csrf.spa() は __Host-XSRF-TOKEN Cookie（ADR-066）の値を X-XSRF-TOKEN Header で送り返させる。
// ZAP の anti-CSRF 処理はフォームのトークンしか扱わないため、unsafe メソッドの要求にこの Header を付ける。
// Cookie が要求に付いていなければ、最後に受け取った Set-Cookie の値を Cookie と Header の両方に付ける。
var ScriptVars = Java.type("org.zaproxy.zap.extension.script.ScriptVars");
var TARGET_HOST = "localhost";
var TARGET_PORT = 4173;
var SAFE_METHODS = ["GET", "HEAD", "TRACE", "OPTIONS"];
var TOKEN_VAR = "xsrf-token";

function sendingRequest(msg, initiator, helper) {
  var header = msg.getRequestHeader();
  var uri = header.getURI();
  if (SAFE_METHODS.indexOf(String(header.getMethod())) >= 0) return;
  if (String(uri.getHost()) !== TARGET_HOST || uri.getPort() !== TARGET_PORT) return;
  var cookie = header.getHeader("Cookie");
  var fromCookie = cookie ? /(?:^|;\s*)__Host-XSRF-TOKEN=([^;]*)/.exec(String(cookie)) : null;
  var token = fromCookie ? fromCookie[1] : ScriptVars.getGlobalVar(TOKEN_VAR);
  if (!token) return;
  header.setHeader("X-XSRF-TOKEN", token);
  if (!fromCookie) header.setHeader("Cookie", (cookie ? cookie + "; " : "") + "__Host-XSRF-TOKEN=" + token);
}

function responseReceived(msg, initiator, helper) {
  var values = msg.getResponseHeader().getHeaderValues("Set-Cookie");
  for (var i = 0; i < values.size(); i++) {
    var match = /^__Host-XSRF-TOKEN=([^;]*)/.exec(String(values.get(i)));
    // 空の値は Cookie の削除なので、保存した値も消す。
    if (match) ScriptVars.setGlobalVar(TOKEN_VAR, match[1] || null);
  }
}
