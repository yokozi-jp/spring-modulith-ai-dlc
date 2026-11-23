package com.example.demo.testkit;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** OpenTelemetry SDK が終えた span をテストで観測する。 */
public final class CapturedSpans implements SpanProcessor {

  /** 共有コンテキストの全テストで終えた span。 */
  private final Queue<SpanData> spans = new ConcurrentLinkedQueue<>();

  @Override
  public void onStart(final Context parentContext, final ReadWriteSpan span) {
    // 終えた span だけを集める。
  }

  @Override
  public boolean isStartRequired() {
    return false;
  }

  @Override
  public void onEnd(final ReadableSpan span) {
    spans.add(span.toSpanData());
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  /** 指定した文字列の属性を持つ span を返す。 */
  public List<SpanData> withAttribute(final String key, final String value) {
    final AttributeKey<String> attribute = AttributeKey.stringKey(key);
    return spans.stream()
        .filter(span -> value.equals(span.getAttributes().get(attribute)))
        .toList();
  }
}
