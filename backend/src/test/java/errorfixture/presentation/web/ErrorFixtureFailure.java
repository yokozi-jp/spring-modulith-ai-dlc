package errorfixture.presentation.web;

import com.example.demo.shared.failure.FailureReason;

/**
 * テストが業務規則の違反に渡す理由。プロダクションコードの理由の定数を借りずに 422 を起こす。
 *
 * <p>com.example.demo の外に置き、プロダクションコードが走査する理由の一覧、OpenAPI の type、title の検査に含めない。
 * パッケージからモジュールの名前を取れないため、定数の本体で {@link #module()} を返す。 enum は抽象になり、走査が定数の本体を持つ enum も拾うことを {@code
 * ProblemTypesTest} が確かめる。
 */
public enum ErrorFixtureFailure implements FailureReason {
  /** フィクスチャの要求を断る。 */
  REJECTED {
    @Override
    public String module() {
      return "error-fixture";
    }
  };

  @Override
  public abstract String module();
}
