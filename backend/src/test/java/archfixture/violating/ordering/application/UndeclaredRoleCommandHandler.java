package archfixture.violating.ordering.application;

/** 違反：applicationHandlersDeclareAuthorization（handle とクラスに @PreAuthorize も @NoRoleRequired もない）。 */
public class UndeclaredRoleCommandHandler {

  /** 役割の判断を宣言しない。 */
  public void handle() {
    // 宣言だけを検査するため、何もしない。
  }
}
