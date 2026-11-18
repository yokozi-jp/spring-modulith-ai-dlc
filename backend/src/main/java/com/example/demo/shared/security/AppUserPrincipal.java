package com.example.demo.shared.security;

/**
 * ログインのときに IdP の識別から引いた、アプリの利用者を表す認証の principal。
 *
 * <p>共通カラムの {@code *_by} は、この型の principal の {@link #userId()} だけを登録する。IdP の {@code sub}
 * を登録しないことを、この型で守る（ADR-095）。
 */
// 認証の principal が実装する型で、ラムダで作る用途がないため。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface AppUserPrincipal {

  /** アプリの利用者の ID（{@code tenancy.m_user.public_id} の UUID の文字列）。 */
  String userId();
}
