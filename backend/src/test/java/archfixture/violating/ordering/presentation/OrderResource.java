package archfixture.violating.ordering.presentation;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：controllersResideInPresentationWeb（presentation.web の外に *Resource と命名して置く）。 */
@RestController
public class OrderResource {

  /** 注文 API の状態を返す。 */
  @GetMapping("/api/orders/health")
  public String health() {
    return "ok";
  }
}
