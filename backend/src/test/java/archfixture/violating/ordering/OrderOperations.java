package archfixture.violating.ordering;

/** 違反：moduleRootTypesAreRecordsEnumsOrQueries（ルートに *Queries 以外の interface を置く）。 */
@SuppressWarnings("PMD.ImplicitFunctionalInterface")
public interface OrderOperations {

  /** 注文を取り消す。 */
  void cancel(String orderId);
}
