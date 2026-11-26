package archfixture.eventcontract;

/**
 * v1 と v3 の fixture があり、v2 がない型。
 *
 * @param orderId 注文の ID
 */
public record VersionGap(String orderId) {}
