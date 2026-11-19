package com.example.demo.tenancy.domain.model;

/** テナントの中の利用者の役割。所属ごとに 1 つ持つ。 */
public enum Role {
  /** テナントの管理者。 */
  ADMIN,
  /** テナントの管理者でない利用者。 */
  MEMBER
}
