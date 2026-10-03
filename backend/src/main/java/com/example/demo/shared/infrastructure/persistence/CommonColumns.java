package com.example.demo.shared.infrastructure.persistence;

import com.example.demo.DemoApplication;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.Field;
import org.jooq.Table;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * INSERT と UPDATE で共通カラムへ登録する値を組み立てる（docs/database/postgresql-common-columns.md）。
 *
 * <p>戻り値の Map は jOOQ の {@code set(Map)} にそのまま渡す。{@code patched_*} はデータパッチだけが更新するため扱わない。
 *
 * <ul>
 *   <li>{@code *_at}：注入した {@link Clock} の現在時刻。
 *   <li>{@code *_by}：利用者の操作では OIDC の ID トークンの {@code sub}。利用者の操作でない処理では {@code *_pgm_cd} と同じ値。
 *   <li>{@code *_pgm_cd}：{@code モジュール名.ユースケースのクラスの単純名}。
 *   <li>{@code *_tx_id}：現在のスパンの trace ID。trace がなければ例外にする。
 * </ul>
 */
@Component
public class CommonColumns {

  /** モジュール名を切り出す基準のベースパッケージ。 */
  private static final String BASE_PACKAGE = DemoApplication.class.getPackageName() + ".";

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
   * @param useCase 処理を実行するユースケースのクラス。{@code *_pgm_cd} の値になる
   * @throws IllegalArgumentException 共通カラムがない、または型が異なる場合
   * @throws IllegalStateException trace がない場合、または利用者を特定できない認証の場合
   */
  public Map<Field<?>, Object> forInsert(final Table<?> table, final Class<?> useCase) {
    final AuditValues audit = audit(useCase);
    final boolean hasUpdated = table.field("updated_at") != null;
    if (!hasUpdated && !table.getName().startsWith(WORK_TABLE_PREFIX)) {
      // ponytail: 更新のカラムを省けるのは接頭辞 w_ のテーブルだけ。changeset の静的検査と同じ接頭辞の判定に頼る。
      throw new IllegalArgumentException(
          "updated_* is required except work tables: table=" + table.getName());
    }
    // INSERT では、更新のカラムにも作成のカラムと同じ値を登録する。
    return toMap(
        Stream.of(
                audit.entries(table, "created_"),
                hasUpdated
                    ? audit.entries(table, "updated_")
                    : Stream.<Entry<Field<?>, Object>>empty(),
                Stream.of(
                    Map.<Field<?>, Object>entry(requiredField(table, "lock_no", Long.class), 1L)))
            .flatMap(Function.identity()));
  }

  /**
   * UPDATE で登録する更新のカラムと、{@code lock_no = lock_no + 1} を返す。
   *
   * @param table 更新先のテーブル
   * @param useCase 処理を実行するユースケースのクラス。{@code *_pgm_cd} の値になる
   * @throws IllegalArgumentException 共通カラムがない、または型が異なる場合
   * @throws IllegalStateException trace がない場合、または利用者を特定できない認証の場合
   */
  public Map<Field<?>, Object> forUpdate(final Table<?> table, final Class<?> useCase) {
    final Field<Long> lockNo = requiredField(table, "lock_no", Long.class);
    return toMap(
        Stream.concat(
            audit(useCase).entries(table, "updated_"),
            Stream.of(Map.<Field<?>, Object>entry(lockNo, lockNo.plus(1)))));
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
  private AuditValues audit(final Class<?> useCase) {
    final String pgmCd = pgmCd(useCase);
    return new AuditValues(Instant.now(clock), operator(pgmCd), pgmCd, traceId());
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

  private static String pgmCd(final Class<?> useCase) {
    final String packageName = useCase.getPackageName() + ".";
    final String simpleName = useCase.getSimpleName();
    if (!packageName.startsWith(BASE_PACKAGE)
        || packageName.length() == BASE_PACKAGE.length()
        || simpleName.isEmpty()) {
      throw new IllegalArgumentException(
          "use case must be a named class in a module: useCase=" + useCase.getName());
    }
    final String relative = packageName.substring(BASE_PACKAGE.length());
    return relative.substring(0, relative.indexOf('.')) + "." + simpleName;
  }

  private static String operator(final String pgmCd) {
    final @Nullable Authentication authentication =
        SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
      return pgmCd;
    }
    final @Nullable String subject =
        authentication.getPrincipal() instanceof OidcUser oidcUser ? oidcUser.getSubject() : null;
    if (subject != null) {
      return subject;
    }
    throw new IllegalStateException(
        "authenticated principal is not an OIDC user with a subject: authentication="
            + authentication.getClass().getName());
  }

  private String traceId() {
    final @Nullable Span span = tracer.currentSpan();
    final String traceId = span == null ? "" : span.context().traceId();
    // OpenTelemetry は trace がないとき、すべて 0 の無効な trace ID を返す。
    if (traceId.matches("0*")) {
      throw new IllegalStateException("no current trace");
    }
    return traceId;
  }

  /** 作成と更新のカラムに共通する、1 回の呼び出しの値。 */
  private record AuditValues(Instant at, String by, String pgmCd, String txId) {

    /** 接頭辞（{@code created_} か {@code updated_}）のカラムと値の組を返す。 */
    private Stream<Entry<Field<?>, Object>> entries(final Table<?> table, final String prefix) {
      return Stream.of(
          Map.entry(requiredField(table, prefix + "at", Instant.class), at),
          Map.entry(requiredField(table, prefix + "by", String.class), by),
          Map.entry(requiredField(table, prefix + "pgm_cd", String.class), pgmCd),
          Map.entry(requiredField(table, prefix + "tx_id", String.class), txId));
    }
  }
}
