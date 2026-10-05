package com.example.demo.testkit;

import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.logs.LogRecordProcessor;
import io.opentelemetry.sdk.logs.ReadWriteLogRecord;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** OpenTelemetry SDK が OTLP へ送る直前の LogRecord をテストで観測する。 */
public final class CapturedLogRecords implements LogRecordProcessor {

  /** 共有コンテキストの全テストで発生した LogRecord。 */
  private final Queue<LogRecordData> records = new ConcurrentLinkedQueue<>();

  @Override
  public void onEmit(final Context context, final ReadWriteLogRecord logRecord) {
    records.add(logRecord.toLogRecordData());
  }

  /** 指定した本文を持つ LogRecord を返す。 */
  public List<LogRecordData> withBody(final String body) {
    return records.stream()
        .filter(
            record ->
                record.getBodyValue() != null && body.equals(record.getBodyValue().asString()))
        .toList();
  }

  /** 指定した instrumentation scope（logger 名）の LogRecord を返す。 */
  public List<LogRecordData> withScope(final String scopeName) {
    return records.stream()
        .filter(record -> scopeName.equals(record.getInstrumentationScopeInfo().getName()))
        .toList();
  }
}
