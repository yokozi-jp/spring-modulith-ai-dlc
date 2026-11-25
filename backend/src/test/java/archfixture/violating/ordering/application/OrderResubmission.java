package archfixture.violating.ordering.application;

import archfixture.violating.shared.eventresubmission.EventPublicationResubmitOnce;

/**
 * 違反：sharedModuleIsUsedOnlyByPersistenceAdapters（application から shared.eventresubmission を直接使う）。
 */
public class OrderResubmission {

  /** application から使ってはいけない shared の再投入の処理。 */
  private final EventPublicationResubmitOnce resubmitOnce;

  /** shared の再投入の処理を受け取る。 */
  public OrderResubmission(final EventPublicationResubmitOnce resubmitOnce) {
    this.resubmitOnce = resubmitOnce;
  }

  /** 失敗した出版を再投入する。 */
  public void resubmit() {
    resubmitOnce.run();
  }
}
