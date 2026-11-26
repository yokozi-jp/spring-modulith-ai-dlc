package archfixture.eventcontract;

import org.jspecify.annotations.Nullable;

/**
 * {@code @Nullable} の {@code note} を足したが、今の形の fixture を足していない型。
 *
 * @param orderId 注文の ID
 * @param note 足した任意の項目
 */
public record NullableComponentAdded(String orderId, @Nullable String note) {}
