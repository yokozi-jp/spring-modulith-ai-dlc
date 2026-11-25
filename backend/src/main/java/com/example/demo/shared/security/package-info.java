/**
 * 認証の語彙を置く。共通カラムの {@code *_by} に登録するアプリの利用者を表す {@link
 * com.example.demo.shared.security.AppUserPrincipal}（ADR-096）と、テナントを選ぶ前の拒否を表す {@link
 * com.example.demo.shared.security.TenantNotSelectedException} である。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かない。{@code
 * TenantNotSelectedException} はテナントの ID も役割も持たず、ログインの状態だけを表す。
 */
@NamedInterface("security")
@NullMarked
package com.example.demo.shared.security;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
