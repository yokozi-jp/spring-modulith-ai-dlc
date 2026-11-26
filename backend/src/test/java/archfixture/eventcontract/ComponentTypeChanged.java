package archfixture.eventcontract;

import java.util.UUID;

/**
 * fixture の {@code tenantId} の型を、{@code value} を持つ record から {@link UUID} に変えた型。
 *
 * @param orderId 注文の ID
 * @param tenantId テナント
 */
public record ComponentTypeChanged(String orderId, UUID tenantId) {}
