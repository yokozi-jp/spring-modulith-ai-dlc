package com.example.demo.shared.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/**
 * {@link TenantContext} の束縛の間だけ、MDC の {@code tenant.id} が束縛したテナントの {@code public_id} になることを検証する。
 */
class TenantContextTest {

  /** MDC の項目の名前。 */
  private static final String KEY = "tenant.id";

  /** 外側で束縛するテナント。 */
  private static final TenantId OUTER =
      new TenantId(UUID.fromString("5d2e8f4a-1b3c-4d6e-9f70-8a1b2c3d4e5f"));

  /** 内側で束縛するテナント。 */
  private static final TenantId INNER =
      new TenantId(UUID.fromString("9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d"));

  @Test
  @DisplayName("束縛の間は MDC の tenant.id が束縛したテナントになり、入れ子の束縛から戻ると外側に戻り、束縛を出ると消える")
  void mdcFollowsBinding() {
    final List<@Nullable String> seen = new ArrayList<>();

    TenantContext.<Void, RuntimeException>call(
        OUTER,
        () -> {
          seen.add(MDC.get(KEY));
          TenantContext.<Void, RuntimeException>call(
              INNER,
              () -> {
                seen.add(MDC.get(KEY));
                return null;
              });
          seen.add(MDC.get(KEY));
          return null;
        });

    assertThat(seen)
        .as("外側、内側、内側から戻った後の MDC")
        .containsExactly(OUTER.toString(), INNER.toString(), OUTER.toString());
    assertThat(MDC.get(KEY)).as("束縛を出た後の MDC").isNull();
  }

  @Test
  @DisplayName("処理が例外を投げても、束縛を出ると MDC の tenant.id は消える")
  void mdcIsRemovedWhenOperationThrows() {
    final IllegalStateException failure = new IllegalStateException("failed");

    assertThatThrownBy(() ->
            TenantContext.<Void, RuntimeException>call(
                OUTER,
                () -> {
                  throw failure;
                }))
        .isSameAs(failure);

    assertThat(MDC.get(KEY)).as("例外で束縛を出た後の MDC").isNull();
  }
}
