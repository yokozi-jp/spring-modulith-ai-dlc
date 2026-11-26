package archfixture.violating.ordering.presentation.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：apiControllersDeclareVersion（version がメジャーバージョンの正の整数でない）。 */
@RestController
@RequestMapping(path = "/api/v{version}/order-minors", version = "1.1")
class OrderMinorVersionController {

  /** 固定の値を返す。 */
  @GetMapping
  /* package */ String minor() {
    return "minor";
  }
}
