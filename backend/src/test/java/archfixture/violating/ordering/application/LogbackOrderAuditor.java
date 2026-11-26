package archfixture.violating.ordering.application;

import ch.qos.logback.classic.Level;

/** 違反：featureCodeUsesOnlySlf4jFacade（機能コードから Logback の API を使う）。 */
public class LogbackOrderAuditor {

  /** 注文の監査ログのレベルを Logback の型で返す。 */
  public Level auditLevel() {
    return Level.INFO;
  }
}
