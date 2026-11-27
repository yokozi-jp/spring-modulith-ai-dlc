package archfixture.violating.shared.locking;

/** ロックを取って定期に動くジョブの時間の設定。フィクスチャなので何もしない。 */
public final class PeriodicLockTiming {

  /** ロックを持つ下限の秒数を返す。 */
  public long lockAtLeastForSeconds() {
    return 0;
  }
}
