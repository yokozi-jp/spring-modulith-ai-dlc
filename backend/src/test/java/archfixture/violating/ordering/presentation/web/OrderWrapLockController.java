package archfixture.violating.ordering.presentation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：controllersDoNotCreateRequests（Controller が Request を作る）。 */
@RestController
@RequestMapping("/api/order-wrap-locks")
class OrderWrapLockController {

  /** 固定のロック番号から Request を作り、その値を返す。 */
  @GetMapping
  /* package */ long wrap() {
    return new WrapLockRequest(1L).lockNo();
  }
}
