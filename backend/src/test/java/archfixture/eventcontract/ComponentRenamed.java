package archfixture.eventcontract;

import java.time.Instant;

/**
 * fixture の {@code orderId} の名前を {@code orderNumber} に変えた型。
 *
 * @param orderNumber 注文の ID
 * @param confirmedAt 確定した時刻
 */
public record ComponentRenamed(String orderNumber, Instant confirmedAt) {}
