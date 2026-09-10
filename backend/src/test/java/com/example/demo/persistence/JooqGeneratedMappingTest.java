package com.example.demo.persistence;

import static com.example.demo.jooq.tables.EventPublication.EVENT_PUBLICATION;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.demo.support.DatabaseTest;
import java.time.Instant;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 生成 jOOQ コードで {@code event_publication} へ insert / select できることの基本検証。
 *
 * <p>{@link DatabaseTest} によりテストごとに自動ロールバックされるので、手動の後始末は不要。 将来のリポジトリ/DAO
 * テストの雛形として、ロールバック隔離のパターンを示す。
 */
@DatabaseTest
class JooqGeneratedMappingTest {

  /** insert / select の対象となる jOOQ コンテキスト。 */
  @Autowired private DSLContext dslContext;

  @Test
  void insertsAndReadsBackGeneratedColumns() {
    final UUID publicationId = UUID.fromString("11111111-1111-1111-1111-111111111111");

    dslContext
        .insertInto(EVENT_PUBLICATION)
        .set(EVENT_PUBLICATION.ID, publicationId)
        .set(EVENT_PUBLICATION.LISTENER_ID, "jooq-mapping-test")
        .set(EVENT_PUBLICATION.EVENT_TYPE, "test.event")
        .set(EVENT_PUBLICATION.SERIALIZED_EVENT, "{}")
        .set(EVENT_PUBLICATION.PUBLICATION_DATE, Instant.parse("2026-09-09T00:00:00Z"))
        .execute();

    final String listenerId =
        dslContext
            .select(EVENT_PUBLICATION.LISTENER_ID)
            .from(EVENT_PUBLICATION)
            .where(EVENT_PUBLICATION.ID.eq(publicationId))
            .fetchOptional(EVENT_PUBLICATION.LISTENER_ID)
            .orElseThrow();

    assertEquals("jooq-mapping-test", listenerId, "生成カラムへ書いた値が読み戻せること");
  }
}
