package archfixture.eventcontract;

/**
 * fixture の {@code confirmedAt} を消した型。
 *
 * @param orderId 注文の ID
 */
public record ComponentRemoved(String orderId) {}
