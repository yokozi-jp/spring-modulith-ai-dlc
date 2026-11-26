package archfixture.violating.ordering.presentation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：apiControllersDeclareVersion（/api/ のパスに v{version} がなく、version を宣言しない）。 */
@RestController
@RequestMapping("/api/order-versions")
class OrderVersionController {

  /** 固定の値を返す。 */
  @GetMapping
  /* package */ String version() {
    return "none";
  }
}
