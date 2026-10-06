package com.example.demo.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ConflictException} のメッセージ、原因、型の形を確かめる。 */
class ConflictExceptionTest {

  @Test
  @DisplayName("メッセージだけで作ると、原因は null である")
  void messageOnlyHasNoCause() {
    final ConflictException exception = new ConflictException("stale");

    assertThat(exception).hasMessage("stale").hasNoCause();
  }

  @Test
  @DisplayName("メッセージと原因で作ると、どちらも保つ")
  void keepsMessageAndCause() {
    final IllegalStateException cause = new IllegalStateException("lock timeout");

    final ConflictException exception = new ConflictException("locked", cause);

    assertThat(exception).hasMessage("locked").hasCause(cause);
  }

  @Test
  @DisplayName("原因に null を渡せる")
  void acceptsNullCause() {
    assertThat(new ConflictException("stale", null)).hasNoCause();
  }

  @Test
  @DisplayName("final な RuntimeException である")
  void isFinalRuntimeException() {
    assertThat(RuntimeException.class).isAssignableFrom(ConflictException.class);
    assertThat(Modifier.isFinal(ConflictException.class.getModifiers())).isTrue();
  }
}
