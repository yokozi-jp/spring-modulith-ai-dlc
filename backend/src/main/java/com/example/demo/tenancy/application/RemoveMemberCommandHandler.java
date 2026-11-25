package com.example.demo.tenancy.application;

import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.idempotency.Idempotency;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** テナントから利用者の所属を外す。 */
@Service
public class RemoveMemberCommandHandler {

  /** テナントを取り出して保存する Repository。 */
  private final TenantRepository tenantRepository;

  /** 同じ冪等性キーの再送に、保存した Result を返す。 */
  private final Idempotency idempotency;

  /** 依存を受け取る。 */
  public RemoveMemberCommandHandler(
      final TenantRepository tenantRepository, final Idempotency idempotency) {
    this.tenantRepository = tenantRepository;
    this.idempotency = idempotency;
  }

  /** ロック番号を確かめて所属を外し、保存する。同じ冪等性キーの再送には、保存した Result を返す。 */
  @Transactional
  public RemoveMemberResult handle(final RemoveMemberCommand command) {
    return idempotency.execute(command, RemoveMemberResult.class, () -> remove(command));
  }

  private RemoveMemberResult remove(final RemoveMemberCommand command) {
    final Tenant tenant =
        tenantRepository
            .findById(new TenantId(UUID.fromString(command.tenantId())))
            .orElseThrow(
                () -> new NotFoundException("tenant not found: tenantId=" + command.tenantId()));
    tenant.ensureLockNo(command.expectedLockNo());
    tenant.removeMember(new UserId(UUID.fromString(command.userId())));
    tenantRepository.update(tenant);
    return new RemoveMemberResult(command.tenantId(), command.userId());
  }
}
