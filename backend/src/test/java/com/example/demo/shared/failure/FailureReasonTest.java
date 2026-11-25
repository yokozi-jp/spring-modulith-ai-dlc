package com.example.demo.shared.failure;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.DemoApplication;
import com.example.demo.ordering.domain.model.OrderFailure;
import com.example.demo.tenancy.domain.model.TenantFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 理由の enum のパッケージから、モジュールの名前を取ることを確かめる（ADR-127）。 */
class FailureReasonTest {

  @Test
  @DisplayName("FailureReason が持つベースパッケージは、DemoApplication のパッケージと同じである")
  void basePackageMatchesApplication() {
    assertThat(FailureReason.BASE_PACKAGE).isEqualTo(DemoApplication.class.getPackageName());
  }

  @Test
  @DisplayName("モジュールの名前は、ベースパッケージの直下のパッケージの名前になる")
  void moduleComesFromPackage() {
    assertThat(OrderFailure.NOT_DRAFT.module()).isEqualTo("ordering");
    assertThat(TenantFailure.LAST_ADMIN.module()).isEqualTo("tenancy");
  }
}
