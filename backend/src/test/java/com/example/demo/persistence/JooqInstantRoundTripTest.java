package com.example.demo.persistence;

import static com.example.demo.jooq.tables.EventPublication.EVENT_PUBLICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.demo.testkit.CommittedDatabaseTest;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 生成 jOOQ フィールドの {@code Instant} が {@code timestamptz} 往復で精度を保つことを、実際に
 * コミットして検証する。読み戻しは別トランザクションで確定値を読むため、保存往復の忠実な検証になる。 後始末は {@link TruncateGeneratedTablesExtension}
 * に委ねる。
 */
@CommittedDatabaseTest
class JooqInstantRoundTripTest {

  /** 生成コードの日時型と PostgreSQL 間の往復を検証するための jOOQ コンテキスト。 */
  @Autowired private DSLContext dslContext;

  @Test
  void generatedJooqInstantRoundTripsThroughTimestampWithTimeZone() {
    final UUID publicationId = UUID.fromString("99999999-9999-9999-9999-999999999999");
    final Instant expected = Instant.parse("2026-09-09T07:44:32.364123Z");

    assertEquals(
        1,
        dslContext
            .insertInto(EVENT_PUBLICATION)
            .set(EVENT_PUBLICATION.ID, publicationId)
            .set(EVENT_PUBLICATION.LISTENER_ID, "jooq-instant-test")
            .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
            .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
            .set(EVENT_PUBLICATION.PUBLICATION_DATE, expected)
            .execute(),
        "生成jOOQフィールドへInstantを保存できること");

    final Instant actual =
        dslContext
            .select(EVENT_PUBLICATION.PUBLICATION_DATE)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(publicationId))
            .fetchOptional(EVENT_PUBLICATION.PUBLICATION_DATE)
            .orElseThrow();

    assertEquals(expected, actual, "生成jOOQフィールドがInstantを維持すること");
  }
}
