package com.example.demo.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.ordering.domain.model.OrderFailure;
import com.example.demo.tenancy.domain.model.TenantFailure;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
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
