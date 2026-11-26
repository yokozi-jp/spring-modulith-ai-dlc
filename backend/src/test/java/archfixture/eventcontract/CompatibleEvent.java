package archfixture.eventcontract;

import org.jspecify.annotations.Nullable;

/**
 * {@code @Nullable} の {@code note} を足し、今の形の fixture を v2 として足した型。違反しない。
 *
 * @param orderId 注文の ID
 * @param note 足した任意の項目
 */
public record CompatibleEvent(String orderId, @Nullable String note) {}
