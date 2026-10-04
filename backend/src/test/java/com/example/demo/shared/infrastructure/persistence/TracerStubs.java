package com.example.demo.shared.infrastructure.persistence;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import org.mockito.Mockito;

/** 共通カラムのテストで使う、現在のスパンを固定した {@link Tracer}。 */
final class TracerStubs {

  private TracerStubs() {}

  /** 現在のスパンの trace ID が {@code traceId} になる Tracer を返す。 */
  /* package */ static Tracer withTraceId(final String traceId) {
    final Tracer tracer = Mockito.mock(Tracer.class);
    final Span span = Mockito.mock(Span.class);
    final TraceContext context = Mockito.mock(TraceContext.class);
    Mockito.when(tracer.currentSpan()).thenReturn(span);
    Mockito.when(span.context()).thenReturn(context);
    Mockito.when(context.traceId()).thenReturn(traceId);
    return tracer;
  }

  /** 現在のスパンがない Tracer を返す。 */
  /* package */ static Tracer withoutSpan() {
    return Mockito.mock(Tracer.class);
  }
}
