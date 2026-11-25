package archfixture.violating.ordering.application;

/** 違反：applicationHandlersDeclareAuthorization（interface を実装した public メソッドに、役割の判断の宣言がない）。 */
public class UndeclaredRoleQueryService implements Runnable {

  /** 役割の判断を宣言しない、interface の実装。 */
  @Override
  public void run() {
    // 宣言だけを検査するため、何もしない。
  }
}
