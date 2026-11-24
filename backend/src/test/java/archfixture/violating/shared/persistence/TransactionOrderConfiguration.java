package archfixture.violating.shared.persistence;

import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 違反：transactionManagementIsNotRedeclared（トランザクションの advisor を Aspect より外側へ動かす）。 */
@EnableTransactionManagement(order = 0)
public class TransactionOrderConfiguration {}
