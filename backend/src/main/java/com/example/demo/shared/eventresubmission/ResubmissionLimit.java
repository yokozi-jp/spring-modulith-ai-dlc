package com.example.demo.shared.eventresubmission;

/**
 * リスナーの再投入の回数の上限（ADR-079）。
 *
 * @param abandonAfterAttempts 出版を諦める回数。Spring Modulith が渡す {@code getCompletionAttempts()}
 *     がこれ以上なら諦める
 */
record ResubmissionLimit(int abandonAfterAttempts) {

  /**
   * Spring Modulith が渡す {@code getCompletionAttempts()} で、出版を諦めるか。既定の {@code
   * ResubmissionProperties} と同じく {@code >=} で比べる。
   */
  /* package */ boolean abandons(final int completionAttempts) {
    return completionAttempts >= abandonAfterAttempts;
  }

  /**
   * 自動で再投入する回数の上限。行の {@code completion_attempts} がこれを超えた出版は再投入しない。
   *
   * <p>Spring Modulith は最初の配信と 1 回目の再投入の失敗に同じ 1 を渡す（ADR-079 の Context の表）。 そのため 2 以上の N は N
   * 回目の再投入の失敗で諦め、1 は最初の配信の失敗で諦めて再投入しない。 1 のリスナーは、配信の途中で落ちて古い出版として {@code FAILED} に戻った出版も再投入しない。
   */
  /* package */ int maxResubmissions() {
    return abandonAfterAttempts == 1 ? 0 : abandonAfterAttempts;
  }
}
