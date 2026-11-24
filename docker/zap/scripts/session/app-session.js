// ZAP の session management script（ADR-119）。
// method: autodetect と headers は、ZAP が利用者として送る要求に Secure の __Host-APP_SESSION を付けない。
// httpsender/carry-host-cookies.js が保存した値を、要求を送る直前に Cookie へ足す。
var ScriptVars = Java.type("org.zaproxy.zap.extension.script.ScriptVars");

function extractWebSession(sessionWrapper) {}

function clearWebSessionIdentifiers(sessionWrapper) {}

function processMessageToMatchSession(sessionWrapper) {
  var header = sessionWrapper.getHttpMessage().getRequestHeader();
  var session = ScriptVars.getGlobalVar("app-session");
  if (!session) return;
  var cookie = header.getHeader("Cookie");
  if (cookie && /(?:^|;\s*)__Host-APP_SESSION=/.test(String(cookie))) return;
  header.setHeader("Cookie", (cookie ? cookie + "; " : "") + "__Host-APP_SESSION=" + session);
}

function getRequiredParamsNames() {
  return [];
}

function getOptionalParamsNames() {
  return [];
}
