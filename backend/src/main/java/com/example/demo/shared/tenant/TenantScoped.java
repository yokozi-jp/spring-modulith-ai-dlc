package com.example.demo.shared.tenant;

/**
 * 発生したテナントを運ぶイベント。
 *
 * <p>{@code @ApplicationModuleListener} の引数のイベントは、この型を実装する。受け取る側のトランザクションが始まる時点で、{@link
 * TenantListenerAspect} がこのテナントを {@link TenantContext} に束縛する。
 */
// イベントの record が実装する型で、ラムダで作る用途がないため。
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface TenantScoped {

  /** イベントが発生したテナント。 */
  TenantId tenantId();
}
