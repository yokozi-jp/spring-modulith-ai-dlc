package com.example.demo.testkit;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.mockito.Mockito;

/** 現在のスパンや trace ID を固定した Micrometer Tracing のスタブ。 */
public final class TracerStubs {

  private TracerStubs() {}

  /** 現在のスパンの trace ID が {@code traceId} になる Tracer を返す。 */
  public static Tracer withTraceId(final String traceId) {
    final Span span = spanWithTraceId(traceId);
    final Tracer tracer = Mockito.mock(Tracer.class);
    Mockito.when(tracer.currentSpan()).thenReturn(span);
    return tracer;
  }

  /** 現在のスパンがない Tracer を返す。 */
  public static Tracer withoutSpan() {
    return Mockito.mock(Tracer.class);
  }

  /** trace ID が {@code traceId} になる Span を返す。 */
  public static Span spanWithTraceId(final String traceId) {
    final TraceContext context = Mockito.mock(TraceContext.class);
    Mockito.when(context.traceId()).thenReturn(traceId);
    final Span span = Mockito.mock(Span.class);
    Mockito.when(span.context()).thenReturn(context);
    return span;
  }
}
