package archfixture.conforming.inventory.application;

/** 注文の在庫を引き当てるユースケースの入力。 */
public record ReserveStockCommand(String orderId) {}
