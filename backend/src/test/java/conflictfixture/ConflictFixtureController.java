package conflictfixture;

import com.example.demo.shared.concurrency.ConflictException;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 競合の例外を投げる HTTP API。例外の内容が応答に出ないことを確かめるためだけに使う。 */
@RestController
@RequestMapping("/api/conflict-fixture")
class ConflictFixtureController {

  /** 版が違う場合の競合を投げる。 */
  @GetMapping("/stale")
  /* package */ void stale() {
    throw new ConflictException("order was updated by another request: orderId=O-1");
  }

  /** 行ロックを待ち切れなかった場合の競合を投げる。 */
  @GetMapping("/locked")
  /* package */ void locked() {
    throw new ConflictException(
        "row is locked by another request: table=t_x",
        new CannotAcquireLockException("SELECT secret_sql"));
  }

  /** 競合ではない IllegalStateException を投げる。 */
  @GetMapping("/illegal-state")
  /* package */ void illegalState() {
    throw new IllegalStateException("order is not confirmed: orderId=O-1");
  }
}
