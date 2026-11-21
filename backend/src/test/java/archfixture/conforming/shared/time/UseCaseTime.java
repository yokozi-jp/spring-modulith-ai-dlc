package archfixture.conforming.shared.time;

import java.time.Instant;

/** ユースケースの現在時刻を返す入口。フィクスチャは束縛を持たず、固定の時刻を返す。 */
public final class UseCaseTime {

  private UseCaseTime() {}

  /** 呼び出し中のユースケースの現在時刻。 */
  public static Instant now() {
    return Instant.parse("2026-10-03T00:00:00Z");
  }
}
