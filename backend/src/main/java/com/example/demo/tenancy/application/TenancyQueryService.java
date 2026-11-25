package com.example.demo.tenancy.application;

import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import com.example.demo.tenancy.TenantMembersDetails;
import com.example.demo.tenancy.domain.model.Membership;
import com.example.demo.tenancy.domain.model.MembershipRepository;
import com.example.demo.tenancy.domain.model.Tenant;
import com.example.demo.tenancy.domain.model.TenantRepository;
import com.example.demo.tenancy.domain.model.UserId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 利用者の所属とテナントの所属の一覧の参照を、Repository で読んだ集約から作る。 */
@Service
class TenancyQueryService implements TenancyQueries {

  /** 所属を取り出す Repository。 */
  private final MembershipRepository membershipRepository;

  /** テナントを取り出す Repository。 */
  private final TenantRepository tenantRepository;

  /** 所属とテナントを取り出す Repository を受け取る。 */
  /* package */ TenancyQueryService(
      final MembershipRepository membershipRepository, final TenantRepository tenantRepository) {
    this.membershipRepository = membershipRepository;
    this.tenantRepository = tenantRepository;
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

  @Override
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional(readOnly = true)
  public Optional<TenantMembersDetails> findMembers(final String tenantId) {
    return tenantRepository
        .findById(new TenantId(UUID.fromString(tenantId)))
        .map(TenancyQueryService::toMembersDetails);
  }

  private static TenantMembersDetails toMembersDetails(final Tenant tenant) {
    return new TenantMembersDetails(
        tenant.lockNo(),
        tenant.members().stream()
            .map(member ->
                new TenantMembersDetails.Member(
                    member.userId().value().toString(), member.role().name()))
            .toList());
  }

  private static MembershipSummary toSummary(final Membership membership) {
    return new MembershipSummary(
        membership.tenantId().value().toString(),
        membership.tenantName(),
        membership.role().name());
  }
}
