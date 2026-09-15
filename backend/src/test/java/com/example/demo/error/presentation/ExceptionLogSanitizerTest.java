package com.example.demo.error.presentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 例外ログの整形が message を除外し、例外グラフを安全にたどることを検証する。 */
class ExceptionLogSanitizerTest {

  @Test
  @DisplayName("suppressed exception の型と frame を残してメッセージを除外する")
  void stackTraceIncludesSuppressedExceptionWithoutMessage() {
    final IllegalStateException exception = new IllegalStateException("private root message");
    exception.setStackTrace(new StackTraceElement[] {frame("Root.java", 10)});
    final IllegalArgumentException suppressed =
        new IllegalArgumentException("private suppressed message");
    suppressed.setStackTrace(new StackTraceElement[] {frame("Suppressed.java", 20)});
    exception.addSuppressed(suppressed);

    final String actual = ExceptionLogSanitizer.stackTrace(exception);

    assertTrue(actual.contains("Root.java:10"), "root の frame が含まれること");
    assertTrue(actual.contains("Suppressed.java:20"), "suppressed の frame が含まれること");
    assertFalse(actual.contains("private root message"), "root の message が含まれないこと");
    assertFalse(actual.contains("private suppressed message"), "suppressed の message が含まれないこと");
  }

  @Test
  @DisplayName("循環する cause を有限の表現へ変換する")
  void stackTraceStopsAtCircularCause() {
    final IllegalStateException first = new IllegalStateException("first secret");
    final IllegalArgumentException second = new IllegalArgumentException("second secret");
    first.initCause(second);
    second.initCause(first);

    final String actual = ExceptionLogSanitizer.stackTrace(first);

    assertTrue(actual.contains("[CIRCULAR REFERENCE:"), "循環参照が明示されること");
    assertFalse(actual.contains("first secret"), "最初の message が含まれないこと");
    assertFalse(actual.contains("second secret"), "cause の message が含まれないこと");
  }

  private static StackTraceElement frame(final String fileName, final int lineNumber) {
    return new StackTraceElement("com.example.demo.Example", "run", fileName, lineNumber);
  }
}
