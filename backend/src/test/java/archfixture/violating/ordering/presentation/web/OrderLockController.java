package archfixture.violating.ordering.presentation.web;

import archfixture.violating.shared.concurrency.ExpectedLockNo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：expectedLockNoIsCreatedOnlyByRequests（Controller が ExpectedLockNo を作る）。 */
@RestController
@RequestMapping("/api/order-locks")
class OrderLockController {

  /** 固定の版から ExpectedLockNo を作り、その値を返す。 */
  @GetMapping
  /* package */ long lockNo() {
    return new ExpectedLockNo(1L).value();
  }
}
