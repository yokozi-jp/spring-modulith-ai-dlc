package com.example.demo.testkit.idempotency;

import com.example.demo.shared.tenancy.TenantId;

/**
 * リスナー 1 つの、冪等性の全件のテストの入力例。リスナーを持つモジュールが、テストの Bean として 1 つ持つ。
 *
 * <p>入力例は業務の本筋を通す。前提の状態を作る処理はイベントを発行しない。発行すると、検査の前にリスナーが動き、1 回目の配信が何も変えなくなるためである。
 */
public interface ListenerExample {

  /** 入力例を届けるリスナー。 */
  ListenerId listener();

  /**
   * 前提の状態をテナントに新しく作り、そのリスナーに届けるイベントを返す。呼ぶたびに、前の呼び出しと重ならない状態を作る。
   *
   * @param tenant 前提の状態を作り、イベントに載せるテナント
   * @return リスナーに届けるイベント
   */
  Object prepare(TenantId tenant);
}
