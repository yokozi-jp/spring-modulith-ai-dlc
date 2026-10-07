package com.example.demo.shared.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@link ConflictException} の種類、メッセージ、原因を持たないこと、型の形を確かめる。 */
class ConflictExceptionTest {

  @Test
  @DisplayName("種類とメッセージを保ち、原因は null である")
  void keepsKindAndMessageWithoutCause() {
    final ConflictException exception =
        new ConflictException(ConflictException.Kind.UNIQUE, "duplicate");

    assertThat(exception.kind()).isEqualTo(ConflictException.Kind.UNIQUE);
    assertThat(exception).hasMessage("duplicate").hasNoCause();
  }

  @Test
  @DisplayName("後から原因を付けられない")
  void rejectsInitCause() {
    final ConflictException exception =
        new ConflictException(ConflictException.Kind.LOCK, "locked");

    assertThatThrownBy(() -> exception.initCause(new IllegalStateException("select secret")))
        .isInstanceOf(IllegalStateException.class);
    assertThat(exception).hasNoCause();
  }

  @Test
  @DisplayName("final な RuntimeException である")
  void isFinalRuntimeException() {
    assertThat(RuntimeException.class).isAssignableFrom(ConflictException.class);
    assertThat(Modifier.isFinal(ConflictException.class.getModifiers())).isTrue();
  }
}
