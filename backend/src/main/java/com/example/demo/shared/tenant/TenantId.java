package com.example.demo.shared.tenant;

import java.util.Objects;
import java.util.UUID;

/**
 * テナントの外に出す ID。{@code tenancy.m_tenant.public_id} の UUID を包む。
 *
 * <p>{@code toString} は UUID の文字列だけを返し、ログとトレースに出した値をそのまま DB の検索に使えるようにする。
 *
 * @param value テナントの {@code public_id}
 */
public record TenantId(UUID value) {

  /** 型の上は null でないが、JSON から読んだ値には null が入りうるため、境界で拒否する。 */
  public TenantId {
    Objects.requireNonNull(value, "value");
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
