package archfixture.violating.ordering.application;

/** 他のクラスから呼ばれる Listener。これ自体は規約どおり。呼ぶ側を listenersAreNotCalled が検出する。 */
// 規約の名前 on に合わせるため、短いメソッド名を許す。
@SuppressWarnings("PMD.ShortMethodName")
public class OrderPaidListener {

  /** 支払いを受け取る。 */
  public void on(final String orderId) {
    // 受け取るだけ。
  }
}
