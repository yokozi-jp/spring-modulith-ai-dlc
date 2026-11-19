package com.example.demo.tenancy;

import java.util.List;
import java.util.Optional;

/** 利用者の所属の参照。 */
public interface TenancyQueries {

  /**
   * 利用者の所属を、テナントの名前の順に返す。所属がなければ空のリストを返す。
   *
   * @param userId アプリの利用者の ID
   */
  List<MembershipSummary> memberships(String userId);

  /**
   * 利用者の、指定したテナントへの所属を返す。所属がなければ空を返す。
   *
   * @param userId アプリの利用者の ID
   * @param tenantId テナントの ID
   */
  Optional<MembershipSummary> membership(String userId, String tenantId);

  /**
   * テナントに所属している利用者と、テナントのロック番号を返す。テナントがなければ空を返す。
   *
   * @param tenantId テナントの ID
   */
  Optional<TenantMembersDetails> findMembers(String tenantId);
}
