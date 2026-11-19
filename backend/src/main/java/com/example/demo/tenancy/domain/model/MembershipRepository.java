package com.example.demo.tenancy.domain.model;

import java.util.List;
import java.util.Optional;

/** 利用者の所属を取り出す。 */
public interface MembershipRepository {

  /** 利用者の所属を、テナントの名前の順に返す。所属がなければ空のリストを返す。 */
  List<Membership> findByUser(UserId userId);

  /** 利用者の、指定したテナントへの所属を返す。所属がなければ空を返す。 */
  Optional<Membership> findByUserAndTenant(UserId userId, TenantId tenantId);
}
