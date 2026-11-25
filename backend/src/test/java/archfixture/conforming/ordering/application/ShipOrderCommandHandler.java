package archfixture.conforming.ordering.application;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 管理者だけに許す、注文の出荷。
 *
 * <p>役割で許す操作は、application の CommandHandler の handle に、役割の名前だけを書いた {@code @PreAuthorize}
 * を付ける（ADR-129）。method security の規則と、application の依存先の規則が、この置き方を通すことを確かめる。
 */
@Service
public class ShipOrderCommandHandler {

  /** 管理者の要求だけを受け、出荷の追跡番号を返す。 */
  @PreAuthorize("hasRole('ADMIN')")
  @Transactional
  public ShipOrderResult handle(final ShipOrderCommand command) {
    return new ShipOrderResult.Shipped("TRACK-" + command.orderId());
  }
}
