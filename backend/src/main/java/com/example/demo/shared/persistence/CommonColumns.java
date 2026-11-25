package com.example.demo.shared.persistence;

import com.example.demo.shared.concurrency.Versioned;
import com.example.demo.shared.security.AppUserPrincipal;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.trace.TraceId;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * INSERT と UPDATE で共通カラムへ登録する値を組み立てる（docs/database/postgresql-common-columns.md）。
 *
 * <p>戻り値の Map は jOOQ の {@code set(Map)} にそのまま渡す。{@code patched_*} はデータパッチだけが更新するため扱わない。
 *
 * <p>{@link #forInsert} は {@link TableWriter} とテストのデータ作成が INSERT で使う。{@link #forUpdate} は {@link
 * TableWriter} と {@link IdempotencyKeyWriter} だけが使い、UPDATE の {@code lock_no} もこの 2 つだけが書く（ADR-054）。
 *
 * <ul>
 *   <li>{@code *_at}：注入した {@link Clock} の現在時刻。
 *   <li>{@code *_by}：利用者の操作では、ログインのときに {@code tenancy} が引いたアプリの利用者の ID（{@link
 *       AppUserPrincipal#userId()}）。 {@code sub} は登録しない。利用者の操作でない処理と、{@code *Listener} の中の処理では
 *       {@code *_pgm_cd} と同じ値。Listener の中では、伝わった利用者の認証を使わない。
 *   <li>{@code *_pgm_cd}：{@link PgmCdAspect} が束縛した、呼び出し中のユースケースの値。
 *   <li>{@code *_tx_id}：現在のスパンの trace ID。trace がなければ例外にする。
 * </ul>
 *
 * <p>{@link #forInsert} と {@link #forUpdate} は、共通カラムがない、または型が異なる場合に {@link
 * IllegalArgumentException} を投げる。trace がない場合、{@code *_pgm_cd} が束縛されていない場合、または Listener の外で
 * principal が {@link AppUserPrincipal} でない認証の場合に {@link IllegalStateException} を投げる。
 */
@Component
public class CommonColumns {

  /** 楽観的ロックの版のカラム名。 */
  /* package */ static final String LOCK_NO = "lock_no";

  /** 共通カラムの名前の全 12 列。{@link TableWriter} と共通処理だけが書き、{@link ColumnValues} は業務の列としては拒否する。 */
  /* package */ static final Set<String> NAMES =
      Set.of(
          "created_at",
          "created_by",
          "created_pgm_cd",
          "created_tx_id",
          "updated_at",
          "updated_by",
          "updated_pgm_cd",
          "updated_tx_id",
          LOCK_NO,
          "patched_at",
          "patched_by",
          "patched_id");

  /** 作成のカラムの接頭辞。 */
  private static final String CREATED = "created_";

  /** 更新のカラムの接頭辞。 */
  private static final String UPDATED = "updated_";

  /** 更新のカラムを省いてよい、追記だけのワークテーブルの接頭辞。 */
  private static final String WORK_TABLE_PREFIX = "w_";

  /** 共通カラムの {@code *_at} に登録する現在時刻を取る。 */
  private final Clock clock;

  /** 共通カラムの {@code *_tx_id} に登録する trace ID を取る。 */
  private final Tracer tracer;

  /** 現在時刻と trace ID の取得元を受け取る。 */
  public CommonColumns(final Clock clock, final Tracer tracer) {
    this.clock = clock;
    this.tracer = tracer;
  }

  /**
   * INSERT で登録する作成と更新のカラム、{@code lock_no = 1} を返す。
   *
   * @param table 登録先のテーブル
   */
  public Map<Field<?>, Object> forInsert(final Table<?> table) {
    final AuditValues audit = audit();
    final boolean hasUpdated = table.field("updated_at") != null;
    if (!hasUpdated && !table.getName().startsWith(WORK_TABLE_PREFIX)) {
      // ponytail: 更新のカラムを省けるのは接頭辞 w_ のテーブルだけ。changeset の静的検査と同じ接頭辞の判定に頼る。
      throw new IllegalArgumentException(
          "updated_* is required except work tables: table=" + table.getName());
    }
    // INSERT では、更新のカラムにも作成のカラムと同じ値を登録する。
    return toMap(
        Stream.of(
                audit.entries(table, CREATED),
                hasUpdated
                    ? audit.entries(table, UPDATED)
                    : Stream.<Entry<Field<?>, Object>>empty(),
                Stream.of(
                    Map.<Field<?>, Object>entry(
                        requiredField(table, LOCK_NO, Long.class), Versioned.FIRST_LOCK_NO)))
            .flatMap(Function.identity()));
  }

  /**
   * 集約のテーブルが、{@link TableWriter} の書く共通カラムをすべて持つことを確かめる。
   *
   * @throws IllegalArgumentException カラムがない、または型が異なる場合
   */
  /* package */ static void requireAuditColumns(final Table<?> table) {
    requiredField(table, LOCK_NO, Long.class);
    for (final String prefix : List.of(CREATED, UPDATED)) {
      AuditValues.fields(table, prefix);
    }
  }

  /**
   * UPDATE で登録する更新のカラムだけを返す。{@code lock_no} は呼び出し側が書く。
   *
   * @param table 更新先のテーブル
   */
  /* package */ Map<Field<?>, Object> forUpdate(final Table<?> table) {
    return toMap(audit().entries(table, UPDATED));
  }

  /**
   * 既存の行を新しい行として取り直す UPDATE で登録する、作成と更新のカラムを返す。{@code lock_no} は呼び出し側が書く。
   *
   * <p>期限切れの {@code Idempotency-Key} の行を新しいキーとして取り直すときに使い、作成のカラムも新しいキーを表すようにする（ADR-126）。
   *
   * @param table 更新先のテーブル
   */
  /* package */ Map<Field<?>, Object> forRecreate(final Table<?> table) {
    final AuditValues audit = audit();
    return toMap(Stream.concat(audit.entries(table, CREATED), audit.entries(table, UPDATED)));
  }

  /**
   * 認証した利用者の、アプリの利用者の ID を返す（ADR-096）。認証がないか匿名なら空を返す。
   *
   * @throws IllegalStateException principal が {@link AppUserPrincipal} でない認証の場合
   */
  /* package */ static Optional<String> currentAppUserId() {
    final @Nullable Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
      return Optional.empty();
    }
    if (authentication.getPrincipal() instanceof AppUserPrincipal user) {
      return Optional.of(user.userId());
    }
    throw new IllegalStateException(
        "authenticated principal is not an app user: authentication="
            + authentication.getClass().getName()
            + ". The principal must implement AppUserPrincipal (SignInOidcUserService returns one;"
            + " clear sessions saved before ADR-096; tests use testkit AppUsers)");
  }

  /**
   * テーブルから名前と型が一致するカラムを返す。
   *
   * @throws IllegalArgumentException カラムがない、または型が異なる場合
   */
  /* package */ static <T> Field<T> requiredField(
      final Table<?> table, final String name, final Class<T> type) {
    final @Nullable Field<?> field = table.field(name);
    if (field == null || !type.equals(field.getType())) {
      throw new IllegalArgumentException(
          "column is missing or has an unexpected type: table="
              + table.getName()
              + ", column="
              + name
              + ", type="
              + type.getSimpleName());
    }
    @SuppressWarnings("unchecked")
    final Field<T> typed = (Field<T>) field;
    return typed;
  }

  /** 1 回の呼び出しで、現在時刻と trace ID を 1 回だけ取る。 */
  private AuditValues audit() {
    final String pgmCd = PgmCdAspect.current();
    final String operator = PgmCdAspect.inListener() ? pgmCd : currentAppUserId().orElse(pgmCd);
    return new AuditValues(Instant.now(clock), operator, pgmCd, traceId());
  }

  /** SQL を決定的にするため、カラムの順序を保った変更できない Map にする。 */
  private static Map<Field<?>, Object> toMap(final Stream<Entry<Field<?>, Object>> entries) {
    return Collections.unmodifiableMap(
        entries.collect(
            Collectors.toMap(
                Entry::getKey,
                Entry::getValue,
                (first, second) -> {
                  throw new IllegalStateException("duplicate common column");
                },
                LinkedHashMap::new)));
  }

  private String traceId() {
    final @Nullable Span span = tracer.currentSpan();
    final String traceId = span == null ? "" : span.context().traceId();
    // OpenTelemetry は trace がないとき、すべて 0 の無効な trace ID を返す。
    if (!TraceId.isValid(traceId)) {
      throw new IllegalStateException("no current trace");
    }
    return traceId;
  }

  /** 作成と更新のカラムに共通する、1 回の呼び出しの値。 */
  private record AuditValues(Instant at, String by, String pgmCd, String txId) {

    /**
     * 接頭辞（{@code created_} か {@code updated_}）の 4 列を、{@code at}、{@code by}、{@code pgm_cd}、{@code
     * tx_id} の順で返す。
     */
    private static List<Field<?>> fields(final Table<?> table, final String prefix) {
      return List.of(
          requiredField(table, prefix + "at", Instant.class),
          requiredField(table, prefix + "by", String.class),
          requiredField(table, prefix + "pgm_cd", String.class),
          requiredField(table, prefix + "tx_id", String.class));
    }

    /** 接頭辞（{@code created_} か {@code updated_}）のカラムと値の組を返す。 */
    private Stream<Entry<Field<?>, Object>> entries(final Table<?> table, final String prefix) {
      final List<Field<?>> fields = fields(table, prefix);
      final List<Object> values = List.of(at, by, pgmCd, txId);
      return IntStream.range(0, fields.size())
          .mapToObj(i -> Map.<Field<?>, Object>entry(fields.get(i), values.get(i)));
    }
  }
}
