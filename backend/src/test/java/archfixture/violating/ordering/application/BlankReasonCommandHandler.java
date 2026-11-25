package archfixture.violating.ordering.application;

import com.example.demo.shared.security.NoRoleRequired;

/** 違反：applicationHandlersDeclareAuthorization（@NoRoleRequired の reason が空と空白だけ）。 */
@NoRoleRequired(reason = "  ")
public class BlankReasonCommandHandler {

  /** reason は必須で省けないため、省いた注釈の代わりに空の値を置く。 */
  @NoRoleRequired(reason = "")
  public void handle() {
    // 宣言だけを検査するため、何もしない。
  }
}
