package com.example.demo.payment.idempotencycheck;

import com.example.demo.testkit.idempotency.ListenerExample;
import com.example.demo.testkit.idempotency.ListenerId;

/**
 * 自己検査のリスナーで、自分の入力例を兼ねるもの。リスナーの ID は、そのクラスのただ 1 つの {@code @ApplicationModuleListener} のメソッドから作る。
 */
// 入力例を兼ねるリスナーのクラスが実装する型であり、ラムダで実装する関数型ではないため、@FunctionalInterface にしない。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
/* package */ interface ListenerProbe extends ListenerExample {

  @Override
  default ListenerId listener() {
    return ListenerId.forListener(getClass());
  }
}
