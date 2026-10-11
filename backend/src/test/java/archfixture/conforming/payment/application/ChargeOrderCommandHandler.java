package archfixture.conforming.payment.application;

import archfixture.conforming.ordering.OrderQueries;
import archfixture.conforming.ordering.OrderSearchCriteria;
import archfixture.conforming.ordering.OrderSummary;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** queryResultStatusIsNotReadByOtherModules が例外にする、注文の状態の名前をログ用に読む CommandHandler。 */
@Service
public class ChargeOrderCommandHandler {

  /** 注文モジュールの読み取り窓口。 */
  private final OrderQueries orderQueries;

  /** 注文モジュールの読み取り窓口を受け取る。 */
  public ChargeOrderCommandHandler(final OrderQueries orderQueries) {
    this.orderQueries = orderQueries;
  }

  /** 顧客の注文の状態の名前を読む。 */
  @Transactional
  public ChargeOrderResult handle(final ChargeOrderCommand command) {
    return new ChargeOrderResult(
        orderQueries.search(new OrderSearchCriteria(command.customerId())).stream()
            .map(OrderSummary::status)
            .toList());
  }
}
