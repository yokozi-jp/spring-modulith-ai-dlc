package archfixture.violating.ordering.application;

import archfixture.violating.ordering.domain.model.OrderId;
import archfixture.violating.ordering.domain.model.OrderRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 違反：domainInterfacesAreImplementedInInfrastructure と
 * repositoryImplementationsAreJooqRepositories（Repository を application で実装する）。
 */
public class InMemoryOrderRepository implements OrderRepository {

  /** 保存した注文 ID。 */
  private final Set<OrderId> ids = ConcurrentHashMap.newKeySet();

  @Override
  public void save(final OrderId id) {
    ids.add(id);
  }
}
