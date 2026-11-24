package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.failure.FailureReason;

/** テナントを選ぶ要求を断る理由。定数ごとに 422 の problem type と title が決まる（ADR-125）。 */
enum TenantSelectionFailure implements FailureReason {
  /** すでにテナントを選んでいる。切り替えるには、ログインし直す。 */
  TENANT_ALREADY_SELECTED,
  /** 所属していないテナントを指定した。 */
  NOT_TENANT_MEMBER
}
