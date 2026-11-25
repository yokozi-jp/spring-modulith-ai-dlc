package com.example.demo.testkit.idempotency;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link CommitGate} の、止めた配信を通す許可の数え方を確かめる。 */
class CommitGateTest {

  @Test
  @DisplayName("門を開けた後に止めた配信を通しても、許可の数があふれない")
  void releaseAfterOpenDoesNotOverflow() {
    final CommitGate gate = new CommitGate(DSL.using(SQLDialect.POSTGRES));
    final CommitGate.Hold hold = gate.close(new ListenerId("probe"));
    gate.open();

    assertThatCode(
            () -> {
              hold.releaseNext();
              hold.releaseNext();
              gate.open();
            })
        .doesNotThrowAnyException();
  }
}
