package com.example.demo.tenancy.application;

import com.example.demo.shared.tenant.TenantId;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import com.example.demo.tenancy.domain.model.Membership;
import com.example.demo.tenancy.domain.model.MembershipRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 利用者の所属の参照を、Repository で読んだ所属から作る。 */
@Service
class TenancyQueryService implements TenancyQueries {

  /** 所属を取り出す Repository。 */
  private final MembershipRepository membershipRepository;

  /** 所属を取り出す Repository を受け取る。 */
  /* package */ TenancyQueryService(final MembershipRepository membershipRepository) {
    this.membershipRepository = membershipRepository;
  }

  @Override
  @Transactional(readOnly = true)
  public List<MembershipSummary> memberships(final String userId) {
    return membershipRepository.findByUser(new UserId(UUID.fromString(userId))).stream()
        .map(TenancyQueryService::toSummary)
        .toList();
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MembershipSummary> membership(final String userId, final String tenantId) {
    return membershipRepository
        .findByUserAndTenant(
            new UserId(UUID.fromString(userId)), new TenantId(UUID.fromString(tenantId)))
        .map(TenancyQueryService::toSummary);
  }

  private static MembershipSummary toSummary(final Membership membership) {
    return new MembershipSummary(
        membership.tenantId().value().toString(),
        membership.tenantName(),
        membership.role().name());
  }
}
