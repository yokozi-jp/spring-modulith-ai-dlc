package archfixture.violating.ordering.presentation.web;

import archfixture.violating.shared.idempotency.IdempotencyKey;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：unsafeHandlersTakeIdempotencyKey（キーを受け取らない、本文の後に受け取る）。 */
@RestController
@RequestMapping("/api/order-keys")
class OrderKeyController {

  /** キーを受け取らずに注文を受け付ける。 */
  @PostMapping
  /* package */ int place(@RequestBody final String body) {
    return body.length();
  }

  /** 本文の後でキーを受け取り、本文の検証がキーの検査より先に動く。 */
  @PostMapping("/{orderId}/ship")
  /* package */ int ship(
      @PathVariable final String orderId,
      @RequestBody final String body,
      final IdempotencyKey idempotencyKey) {
    return orderId.length() + body.length() + idempotencyKey.hashCode();
  }
}
