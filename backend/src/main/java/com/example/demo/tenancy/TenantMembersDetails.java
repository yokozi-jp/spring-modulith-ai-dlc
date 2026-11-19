package com.example.demo.tenancy;

import java.util.List;

/**
 * テナントの所属の一覧の参照の結果。
 *
 * @param lockNo テナントのロック番号。所属を変える操作の本文で送り返す
 * @param members 所属している利用者。所属した順に並ぶ
 */
public record TenantMembersDetails(long lockNo, List<Member> members) {

  /** 一覧を変更できないリストとして持つ。 */
  public TenantMembersDetails {
    members = List.copyOf(members);
  }

  /**
   * 所属している利用者と、その役割。
   *
   * @param userId 利用者の ID
   * @param role テナントの中の役割のコード値（ADMIN か MEMBER）
   */
  public record Member(String userId, String role) {}
}
