/**
 * 操作しているテナントの語彙を置く。テナントの ID を包む {@link com.example.demo.shared.tenant.TenantId}、テナントを運ぶイベントの型
 * {@link com.example.demo.shared.tenant.TenantScoped}、呼び出しの間だけテナントを束縛する {@link
 * com.example.demo.shared.tenant.TenantContext} と {@link
 * com.example.demo.shared.tenant.TenantListenerAspect} である（ADR-104）。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かず、DB に依存しない。
 */
@NamedInterface("tenant")
@NullMarked
package com.example.demo.shared.tenant;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
