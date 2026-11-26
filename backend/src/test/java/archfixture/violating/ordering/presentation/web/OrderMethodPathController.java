package archfixture.violating.ordering.presentation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：apiControllersDeclareVersion（メソッドの mapping に /api/ の絶対のパスを書き、クラスの version を迂回する）。 */
@RestController
@RequestMapping(path = "/api/v{version}/order-methods", version = "1")
class OrderMethodPathController {

  /** 固定の値を返す。 */
  @GetMapping("/api/v1/order-methods/absolute")
  /* package */ String absolute() {
    return "absolute";
  }
}
