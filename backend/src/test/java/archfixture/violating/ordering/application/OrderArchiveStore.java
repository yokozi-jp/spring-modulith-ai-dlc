package archfixture.violating.ordering.application;

import java.util.List;
import org.springframework.stereotype.Repository;

/** 違反：repositoriesResideInPersistenceAdapters（{@code @Repository} のクラスを application に置く）。 */
@Repository
public class OrderArchiveStore {

  /** 保管した注文 ID を返す。 */
  public List<String> archivedOrderIds() {
    return List.of();
  }
}
