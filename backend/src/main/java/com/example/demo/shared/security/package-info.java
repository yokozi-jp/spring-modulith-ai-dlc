/**
 * 認証の principal の語彙を置く（ADR-093）。共通カラムの {@code *_by} に登録するアプリの利用者を表す {@link
 * com.example.demo.shared.security.AppUserPrincipal} である。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かず、Spring に依存しない。
 */
@NamedInterface("security")
@NullMarked
package com.example.demo.shared.security;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
