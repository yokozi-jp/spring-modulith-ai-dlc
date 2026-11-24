/**
 * 操作しているテナントの語彙を置く。テナントの ID を包む {@link com.example.demo.shared.tenancy.TenantId}、テナントを運ぶイベントの型
 * {@link com.example.demo.shared.tenancy.TenantScoped}、呼び出しの間だけテナントを束縛する {@link
 * com.example.demo.shared.tenancy.TenantContext} である（ADR-105）。リスナーの束縛は {@link
 * com.example.demo.shared.tenancy.TenantListenerAspect} が行う。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。ただし Domain（{@code
 * domain.model}、{@code domain.service}）が使ってよいのは {@link com.example.demo.shared.tenancy.TenantId}
 * だけで、{@link com.example.demo.shared.tenancy.TenantContext} は Application が読んで引数で渡す。業務の概念を置かず、DB
 * に依存しない。
 */
@NamedInterface("tenancy")
@NullMarked
package com.example.demo.shared.tenancy;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
