/**
 * リスナーの呼び出しの間だけ、イベントのテナントを束縛する Aspect（{@code TenantListenerAspect}、ADR-105）を置く。
 *
 * <p>テナントの語彙は {@code tenancy} に置く。この Aspect は Spring AOP に依存するので、どの層からも使う語彙の {@code tenancy}
 * には置かない。
 */
@NullMarked
package com.example.demo.shared.tenantbinding;

import org.jspecify.annotations.NullMarked;
