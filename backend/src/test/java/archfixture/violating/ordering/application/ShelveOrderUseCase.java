package archfixture.violating.ordering.application;

/**
 * 違反：commandHandlersDoNotImplementInterfaces（CommandHandler に実装させ、呼び出し側にこの interface だけを見せて
 * CommandHandler への依存の検査を迂回する）。
 */
@FunctionalInterface
public interface ShelveOrderUseCase {

  /** 注文を保留にする。 */
  ShelveOrderResult handle(ShelveOrderCommand command);
}
