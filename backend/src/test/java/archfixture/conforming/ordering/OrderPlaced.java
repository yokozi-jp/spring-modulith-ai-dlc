package archfixture.conforming.ordering;

import java.time.Instant;

/** 注文を受け付けたことを他モジュールへ知らせるイベント。 */
public record OrderPlaced(String orderId, String customerId, Instant placedAt) {}
