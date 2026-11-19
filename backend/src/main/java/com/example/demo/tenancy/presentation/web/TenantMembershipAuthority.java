package com.example.demo.tenancy.presentation.web;

import java.io.Serial;
import org.springframework.security.core.GrantedAuthority;

/**
 * テナントを選んだ認証だけが持つ、選んだテナントと、その所属の役割の権限。
 *
 * <p>テナントを選んだかどうかは、認証がこの権限を持つかどうかだけで表す。権限の名前は役割から {@code ROLE_ADMIN} か {@code ROLE_MEMBER} にする。
 *
 * @param tenantId 選んだテナントの ID
 * @param role 所属の役割のコード値（ADMIN か MEMBER）
 */
public record TenantMembershipAuthority(String tenantId, String role) implements GrantedAuthority {

  @Serial private static final long serialVersionUID = 1L;

  @Override
  public String getAuthority() {
    return "ROLE_" + role;
  }
}
