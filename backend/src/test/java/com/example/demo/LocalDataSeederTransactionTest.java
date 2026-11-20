package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.LocalDataSeeder.Mode;
import com.example.demo.LocalDataSeeder.Outcome;
import com.example.demo.testkit.DatabaseTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * シーダーを、Spring のトランザクションの外で、本番の入口 {@code run} と同じ jOOQ のトランザクションで実行しても、{@code TableWriter}
 * の書き込みが通ることを確かめる。
 *
 * <p>{@code LocalDataSeederTest} は Spring のテストのトランザクションの中で動くため、{@code run} が使う jOOQ
 * 自身のトランザクションを通らない。{@code TableWriter} がトランザクションの有無を Spring の状態で調べると、{@code task e2e-up} と {@code
 * task be-seed} だけが失敗する。
 */
@DatabaseTest
class LocalDataSeederTransactionTest {

  /** test-user と test-admin を所属させる発行者。 */
  private static final String ISSUER = "http://127.0.0.1:8081/realms/spring-modulith";

  /** テスト用の DB の接続を取る。Spring のトランザクションとは別の接続になる。 */
  @Autowired private DataSource dataSource;

  @Test
  @DisplayName("run と同じ jOOQ のトランザクションで業務データを入れ直せる")
  void seedsInsideJooqTransactionOutsideSpring() throws SQLException {
    final List<Outcome> outcomes = new ArrayList<>();
    try (Connection connection = dataSource.getConnection()) {
      // 別の接続の変更を残さないため、トランザクションの最後に例外でロールバックする。
      assertThatThrownBy(() ->
              DSL.using(connection, SQLDialect.POSTGRES)
                  .transactionResult(cfg -> {
                    outcomes.add(
                        new LocalDataSeeder(
                                Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC),
                                ISSUER)
                            .seed(cfg.dsl(), Mode.RESET));
                    throw new IllegalStateException("rollback");
                  }))
          .hasMessage("rollback");
    }

    assertThat(outcomes).containsExactly(Outcome.SEEDED);
  }
}
