package archfixture.violating.ordering.application;

import archfixture.violating.shared.locking.PeriodicLockTiming;

/** 違反：sharedModuleIsUsedOnlyByPersistenceAdapters（application から shared.locking を直接使う）。 */
public class OrderLocking {

  /** application から使ってはいけない shared のロックの設定。 */
  private final PeriodicLockTiming timing;

  /** shared のロックの設定を受け取る。 */
  public OrderLocking(final PeriodicLockTiming timing) {
    this.timing = timing;
  }

  /** ロックを持つ下限の秒数を返す。 */
  public long lockAtLeastForSeconds() {
    return timing.lockAtLeastForSeconds();
  }
}
