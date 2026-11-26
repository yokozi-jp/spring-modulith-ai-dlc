package archfixture.eventcontract;

/**
 * fixture があるが、受けるリスナーがない型。
 *
 * @param orderId 注文の ID
 */
public record WithoutListener(String orderId) {}
