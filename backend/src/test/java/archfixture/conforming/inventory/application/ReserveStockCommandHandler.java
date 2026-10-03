package archfixture.conforming.inventory.application;

import archfixture.conforming.order.OrderQueries;
import java.util.NoSuchElementException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 注文の在庫を引き当てる。 */
@Service
public class ReserveStockCommandHandler {

  /** 注文モジュールの読み取り窓口。 */
  private final OrderQueries orderQueries;

  /** 注文モジュールの読み取り窓口を受け取る。 */
  public ReserveStockCommandHandler(final OrderQueries orderQueries) {
    this.orderQueries = orderQueries;
  }

  /** 注文の明細を読み、在庫を引き当てる。 */
  @Transactional
  public ReserveStockResult handle(final ReserveStockCommand command) {
    return orderQueries
        .findDetails(command.orderId())
        .map(details -> new ReserveStockResult(details.orderId()))
        .orElseThrow(
            () -> new NoSuchElementException("order not found: orderId=" + command.orderId()));
  }
}
