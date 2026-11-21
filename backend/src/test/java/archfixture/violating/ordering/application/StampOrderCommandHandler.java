package archfixture.violating.ordering.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** 違反：commandHandlersAndListenersDoNotReadClock（CommandHandler が時計を受け取って読む）。 */
@Service
public class StampOrderCommandHandler {

  /** 処理の中で読む時計。 */
  private final Clock clock;

  /** 時計を受け取る。 */
  public StampOrderCommandHandler(final Clock clock) {
    this.clock = clock;
  }

  /** 時計を読んだ時刻を返す。 */
  public Instant handle() {
    return Instant.now(clock);
  }
}
