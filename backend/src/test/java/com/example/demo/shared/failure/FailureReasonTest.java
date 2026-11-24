package com.example.demo.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.ordering.domain.model.OrderFailure;
import com.example.demo.tenancy.domain.model.TenantFailure;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 理由の enum の定数から、モジュールの名前と kebab-case の problem type と title のキーを作ることを確かめる（ADR-125）。 */
class FailureReasonTest {

  private static Stream<Arguments> reasons() {
    return Stream.of(
        Arguments.of(
            OrderFailure.PRODUCT_NOT_FOUND,
            "/problems/ordering/product-not-found",
            "problem.title.ordering.product-not-found"),
        Arguments.of(
            TenantFailure.LAST_ADMIN,
            "/problems/tenancy/last-admin",
            "problem.title.tenancy.last-admin"));
  }

  @Test
  @DisplayName("ベースパッケージの外の理由は、type を作るときに IllegalStateException になる")
  void reasonOutsideBasePackageIsRejected() {
    // Proxy のクラスは jdk.proxy のパッケージに作られ、ベースパッケージの外になる。default method は本物を呼ぶ。
    final FailureReason outside =
        (FailureReason)
            Proxy.newProxyInstance(
                Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {FailureReason.class},
                (proxy, method, args) ->
                    method.isDefault()
                        ? InvocationHandler.invokeDefault(proxy, method, args)
                        : "OUTSIDE");

    assertThatThrownBy(outside::type)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("com.example.demo.");
  }

  @ParameterizedTest
  @MethodSource("reasons")
  @DisplayName(
      "type は /problems/<module>/<kebab-case>、title のキーは problem.title.<module>.<kebab-case> になる")
  void typeAndTitleKeyComeFromPackageAndName(
      final FailureReason reason, final String type, final String titleKey) {
    assertThat(reason.type()).isEqualTo(type);
    assertThat(reason.titleKey()).isEqualTo(titleKey);
  }
}
