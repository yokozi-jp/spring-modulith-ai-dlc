package com.example.demo.tenancy.application;

import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.domain.model.Role;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** テナントに所属している利用者の役割を変える。 */
@Service
public class ChangeMemberRoleCommandHandler {

  /** テナントを取り出して保存する Repository。 */
  private final TenantRepository tenantRepository;

  /** 依存を受け取る。 */
  public ChangeMemberRoleCommandHandler(final TenantRepository tenantRepository) {
    this.tenantRepository = tenantRepository;
  }

  /** ロック番号を確かめて役割を変え、保存する。 */
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public ChangeMemberRoleResult handle(final ChangeMemberRoleCommand command) {
    final Tenant tenant =
        tenantRepository
            .findById(new TenantId(UUID.fromString(command.tenantId())))
            .orElseThrow(
                () -> new NotFoundException("tenant not found: tenantId=" + command.tenantId()));
    tenant.ensureLockNo(command.expectedLockNo());
    tenant.changeRole(new UserId(UUID.fromString(command.userId())), Role.valueOf(command.role()));
    tenantRepository.update(tenant);
    return new ChangeMemberRoleResult(command.tenantId(), command.userId());
  }
}
