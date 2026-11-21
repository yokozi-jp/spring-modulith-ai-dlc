package com.example.demo.tenancy.domain.model;

import com.example.demo.shared.tenant.TenantId;
import java.util.Optional;

/** テナントの集約を取り出して保存する。 */
public interface TenantRepository {

  /** テナントを、所属している利用者とともに返す。なければ空を返す。 */
  Optional<Tenant> findById(TenantId id);

  /**
   * テナントの所属の変更を保存する。外した利用者の所属を消し、役割が変わった所属を更新する。
   *
   * @throws com.example.demo.shared.concurrency.ConflictException 読んだ後に、ほかのリクエストがテナントを更新した場合
   */
  void update(Tenant tenant);
}
