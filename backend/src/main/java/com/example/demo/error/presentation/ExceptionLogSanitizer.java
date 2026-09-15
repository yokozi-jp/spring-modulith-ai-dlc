package com.example.demo.error.presentation;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** 例外メッセージを除外し、診断に必要な型と stack frame だけをログ用に整形する。 */
final class ExceptionLogSanitizer {

  private ExceptionLogSanitizer() {}

  /** cause と suppressed exception を含む、PII を持たない stack trace を返す。 */
  /* package */ static String stackTrace(final Throwable exception) {
    final StringBuilder result = new StringBuilder();
    final Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
    append(exception, "", "", visited, result);
    return result.toString();
  }

  private static void append(
      final Throwable exception,
      final String caption,
      final String indent,
      final Set<Throwable> visited,
      final StringBuilder result) {
    if (!visited.add(exception)) {
      result
          .append(indent)
          .append(caption)
          .append("[CIRCULAR REFERENCE: ")
          .append(exception.getClass().getName())
          .append("]\n");
      return;
    }

    result.append(indent).append(caption).append(exception.getClass().getName()).append('\n');
    for (final StackTraceElement frame : exception.getStackTrace()) {
      result.append(indent).append("\tat ").append(frame).append('\n');
    }
    for (final Throwable suppressed : exception.getSuppressed()) {
      append(suppressed, "Suppressed: ", indent + "\t", visited, result);
    }
    if (exception.getCause() != null) {
      append(exception.getCause(), "Caused by: ", indent, visited, result);
    }
  }
}
