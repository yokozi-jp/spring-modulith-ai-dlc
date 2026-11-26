package archfixture.eventcontract;

/**
 * リスナーが受けるが、fixture のない型。
 *
 * @param orderId 注文の ID
 */
public record WithoutFixture(String orderId) {}
