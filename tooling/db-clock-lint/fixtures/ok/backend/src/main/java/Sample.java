import java.time.Clock;
import java.time.Instant;

/** SQL の CURRENT_DATE と now() は使わない。 */
class Sample {
  // SELECT date_trunc('day', x) は使わない。
  static final String TENANT = "SELECT current_setting('app.current_tenant')::uuid";
  static final String IDENT = "SELECT current_date_value, now_playing, ordered_at FROM t";

  Instant read(Clock clock) {
    return Instant.now(clock);
  }
}
