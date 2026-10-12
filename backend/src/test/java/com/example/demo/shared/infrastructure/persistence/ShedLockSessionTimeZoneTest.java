package com.example.demo.shared.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.jooq.modulith.Tables;
import com.example.demo.testkit.CommittedDatabaseTest;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jooq.JooqLockProvider;
import org.jooq.DSLContext;
import org.jooq.ExecuteContext;
import org.jooq.ExecuteListener;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.DefaultExecuteListenerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * ShedLock のロックの期限が、接続のセッションの TimeZone によらず守られることを検証する（ADR-077）。
 *
 * <p>ShedLock の jOOQ の provider は、期限を {@code cast(current_timestamp(6) as timestamp)}（セッションの
 * TimeZone での時刻帯のない値。{@code localtimestamp(6)} と同じ）で書き込んで比べる。 表の列が {@code TIMESTAMP WITH TIME ZONE}
 * なら、書き込みと比較の双方が同じセッションの TimeZone で変換されるため、実際の時刻に戻る。列を {@code TIMESTAMP} にすると、 TimeZone
 * が違う接続の間で期限が数時間ずれ、別の接続が保持中のロックを 奪う。このテストは、その変更を失敗させる。
 *
 * <p>アプリの provider は UTC のセッションで動く（{@code connection-init-sql}）。もう一方の provider は、同じ DB へ別の TimeZone
 * のセッションで接続する。 ロックの名前はテストごとに新しくし、終わったら行を消す（ShedLock は作った行を覚えるため、同じ名前を消して使い回さない）。
 */
@CommittedDatabaseTest
class ShedLockSessionTimeZoneTest {

  /** ロックを持つ時間。テストの間に切れない長さにする。 */
  private static final Duration HELD_FOR = Duration.ofMinutes(10);

  /** ロックを取る時刻。ShedLock は期限を DB の時刻で決めるため、値は結果に影響しない。 */
  private static final Instant CREATED_AT = Instant.parse("2026-10-10T01:02:03.123456Z");

  /** アプリの provider。セッションの TimeZone は UTC。 */
  @Autowired private LockProvider utcProvider;

  /** 接続先の設定。 */
  @Autowired private Environment environment;

  /** ロックの行を動かす DB。 */
  @Autowired private DSLContext dsl;

  /** このテストが開いた別の TimeZone の DataSource。 */
  private final List<HikariDataSource> openedDataSources = new CopyOnWriteArrayList<>();

  /** このテストが使ったロックの名前。 */
  private final List<String> lockNames = new CopyOnWriteArrayList<>();

  /** 別の TimeZone の接続が実行した SQL。 */
  private final List<String> executedSql = new CopyOnWriteArrayList<>();

  @AfterEach
  void cleanUp() {
    lockNames.forEach(
        name -> dsl.deleteFrom(Tables.SHEDLOCK).where(Tables.SHEDLOCK.NAME.eq(name)).execute());
    openedDataSources.forEach(HikariDataSource::close);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Asia/Tokyo", "America/Los_Angeles"})
  @DisplayName("UTC のセッションが持つロックを、別の TimeZone のセッションは取れず、期限が切れたら取れる")
  void otherZoneCannotTakeLockHeldInUtcUntilItExpires(final String zone) {
    final LockProvider other = providerIn(zone);
    final String name = newLockName();

    final Optional<SimpleLock> held = utcProvider.lock(configuration(name));

    assertThat(held).as("UTC が取る").isPresent();
    assertThat(other.lock(configuration(name))).as("%s は取れない", zone).isEmpty();

    expire(name);

    assertThat(other.lock(configuration(name))).as("期限が切れたら %s は取れる", zone).isPresent();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"Asia/Tokyo", "America/Los_Angeles"})
  @DisplayName("別の TimeZone のセッションが持つロックを、UTC のセッションは取れず、期限が切れたら取れる")
  void utcCannotTakeLockHeldInOtherZoneUntilItExpires(final String zone) {
    final LockProvider other = providerIn(zone);
    final String name = newLockName();

    final Optional<SimpleLock> held = other.lock(configuration(name));

    assertThat(held).as("%s が取る", zone).isPresent();
    assertThat(utcProvider.lock(configuration(name))).as("UTC は取れない").isEmpty();

    expire(name);

    assertThat(utcProvider.lock(configuration(name))).as("期限が切れたら UTC は取れる").isPresent();
  }

  @Test
  @DisplayName("ShedLock は時刻を、時刻帯のない現在時刻（cast(current_timestamp(6) as timestamp)）で書き込む")
  void shedLockWritesZonelessCurrentTimestamp() {
    final String name = newLockName();

    providerIn("Asia/Tokyo").lock(configuration(name));

    assertThat(executedSql)
        .as("この式が timestamptz の列に書かれるため、セッションの TimeZone で変換される")
        .anySatisfy(sql -> assertThat(sql).contains("cast(current_timestamp(6) as timestamp)"));
  }

  /** 別の TimeZone のセッションで接続する provider を作る。 */
  // DataSource は provider の寿命の間だけ開いておく必要があるため、openedDataSources に記録して cleanUp で閉じる。
  @SuppressWarnings("PMD.CloseResource")
  private LockProvider providerIn(final String zone) {
    final HikariConfig config = new HikariConfig();
    config.setJdbcUrl(required("spring.datasource.url"));
    config.setUsername(required("spring.datasource.username"));
    config.setPassword(required("spring.datasource.password"));
    config.setMaximumPoolSize(1);
    config.setConnectionInitSql("SET TIME ZONE '" + zone + "'");
    final HikariDataSource dataSource = new HikariDataSource(config);
    openedDataSources.add(dataSource);
    final ExecuteListener recorder =
        new ExecuteListener() {
          @Override
          public void executeStart(final ExecuteContext ctx) {
            executedSql.add(ctx.sql());
          }
        };
    final DefaultConfiguration configuration = new DefaultConfiguration();
    configuration.set(dataSource);
    configuration.set(SQLDialect.POSTGRES);
    configuration.set(new DefaultExecuteListenerProvider(recorder));
    final DSLContext otherDsl = DSL.using(configuration);
    assertThat(otherDsl.fetchValue("SHOW TIME ZONE")).as("セッションの TimeZone").isEqualTo(zone);
    return new JooqLockProvider(EventPublicationResubmissionConfig.lockDsl(otherDsl));
  }

  private String required(final String key) {
    final String value = environment.getProperty(key);
    assertThat(value).as(key).isNotBlank();
    return value;
  }

  private String newLockName() {
    final String name = "session-time-zone-" + UUID.randomUUID();
    lockNames.add(name);
    return name;
  }

  private static LockConfiguration configuration(final String name) {
    return new LockConfiguration(CREATED_AT, name, HELD_FOR, Duration.ZERO);
  }

  /** ロックの期限を過去にする。期限は DB の時刻で決まり、アプリの時計では進まないため。 */
  private void expire(final String name) {
    dsl.update(Tables.SHEDLOCK)
        .set(Tables.SHEDLOCK.LOCK_UNTIL, Tables.SHEDLOCK.LOCKED_AT)
        .where(Tables.SHEDLOCK.NAME.eq(name))
        .execute();
  }
}
