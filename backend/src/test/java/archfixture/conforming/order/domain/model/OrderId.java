package archfixture.conforming.order.domain.model;

import java.util.UUID;

/** 注文 ID の値オブジェクト。 */
public record OrderId(String value) {

  /** 新しい注文 ID を採番する。 */
  public static OrderId newId() {
    return new OrderId(UUID.randomUUID().toString());
  }
}
