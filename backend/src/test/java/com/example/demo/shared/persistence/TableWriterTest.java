package com.example.demo.shared.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static com.example.demo.shared.persistence.FixtureAggregates.DETAILS;
import static com.example.demo.shared.persistence.FixtureAggregates.ITEM;
import static com.example.demo.shared.persistence.FixtureAggregates.ITEM_ONLY;
import static com.example.demo.shared.persistence.FixtureAggregates.detail;
import static com.example.demo.shared.persistence.FixtureAggregates.detailRows;
import static com.example.demo.shared.persistence.FixtureAggregates.itemRoot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.shared.failure.NotFoundException;
import com.example.demo.shared.persistence.FixtureAggregates.Detail;
import com.example.demo.shared.persistence.FixtureAggregates.Item;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import com.example.demo.testkit.TracerStubs;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** {@link TableWriter} の SQL と件数の判定を、テスト専用のテーブルと実 PostgreSQL で確かめる。各テストはロールバックする。 */
// 入口ごとの成功と失敗の条件をテストに分けるため、メソッドの数の上限を外す。
// 行の値はテストごとに読み下せるよう、定数にせず文字列のまま書く。
// 共有の集約の宣言と版の作成を static import するため、static import の数の上限を外す。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.TooManyStaticImports"})
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class TableWriterTest {

  /** lock_no を持たないテーブル。 */
  private static final Table<Record> NO_LOCK_TABLE = DSL.table(DSL.name("t_no_lock"));

  /** 列を書く子の明細番号。 */
  private static final int FIRST_DETAIL = 1;

  /** 更新の時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-04T01:02:03.123456Z");

  /** 行を作った時刻。 */
  private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00.000001Z");

  /** 更新のユースケースとして束縛する pgm_cd。 */
  private static final String PGM_CD = "shared.TableWriterTest";

  /** 行を作るときに束縛する pgm_cd。 */
  private static final String SEED_PGM_CD = "shared.Seed";

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** jOOQ が描く SQL 文の始まり。キーワードの後に引用符付きのテーブル名が続く。 */
  private static final Pattern SQL_STATEMENT =
      Pattern.compile("(?i)\\b(insert into|update|select|delete from)\\s+\"");

  /** アプリロールで接続した jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 行を作るときの共通カラムの値。 */
  private CommonColumns seedColumns;

  /** トランザクションの有無と読み取り専用を切り替えて呼ぶ。 */
  @Autowired private PlatformTransactionManager transactionManager;

  /** 検証対象。 */
  private TableWriter writer;

  @BeforeEach
  void setUp() {
    seedColumns =
        new CommonColumns(Clock.fixed(CREATED, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID));
    writer =
        new TableWriter(
            dsl,
            new CommonColumns(Clock.fixed(NOW, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID)));
  }

  @Test
  @DisplayName("update は業務の列と updated_* を書き、版を期待値 + 1 にし、created_* を変えない")
  void updateWritesRootColumnsAndAdvancesVersion() {
    insertItem(1L, "before");

    runInUseCase(() -> updateItem(1L, 1L, "after"));

    final Record row = item(1L);
    assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).as("業務の列").isEqualTo("after");
    assertThat(row.get(FIXTURE_ITEM.LOCK_NO)).as("版").isEqualTo(2L);
    assertThat(row.get(FIXTURE_ITEM.UPDATED_AT)).as("updated_at").isEqualTo(NOW);
    assertThat(row.get(FIXTURE_ITEM.UPDATED_PGM_CD)).as("updated_pgm_cd").isEqualTo(PGM_CD);
    assertThat(row.get(FIXTURE_ITEM.CREATED_AT)).as("created_at").isEqualTo(CREATED);
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD)).as("created_pgm_cd").isEqualTo(SEED_PGM_CD);
  }

  @Test
  @DisplayName("update は業務の列がなくても、版と updated_* を進める")
  void updateWithoutBusinessColumnsAdvancesVersion() {
    insertItem(1L, "before");

    runInUseCase(() -> save(1L, 1L, set -> {}, List.of()));

    assertThat(item(1L).get(FIXTURE_ITEM.LOCK_NO)).isEqualTo(2L);
    assertThat(item(1L).get(FIXTURE_ITEM.UPDATED_AT)).isEqualTo(NOW);
  }

  @Test
  @DisplayName("update は版が違えば、原因なしの競合の例外を投げ、行を変えない")
  void updateWithStaleVersionConflicts() {
    insertItem(1L, "before");
    makeStale(1L);

    assertThatThrownBy(() -> runInUseCase(() -> updateItem(1L, 1L, "after")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("table=t_fixture_item")
        .hasMessageContaining("item_id=1")
        .hasMessageContaining("expectedLockNo=1")
        .hasNoCause()
        .satisfies(thrown -> assertConflictWithoutSql(thrown, ConflictException.Kind.VERSION));
    assertThat(item(1L).get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("before");
  }

  @Test
  @DisplayName("update は一意の列の行がなければ NotFoundException を投げる")
  void updateWithoutRowIsNotFound() {
    assertThatThrownBy(() -> runInUseCase(() -> updateItem(1L, 1L, "after")))
        .isInstanceOf(NotFoundException.class)
        .hasMessageContaining("table=t_fixture_item");
  }

  @Test
  @DisplayName("update は一意の列が 2 行に合えば IllegalStateException を投げる")
  void updateMatchingTwoRowsIsRejected() {
    insertItem(1L, "same");
    insertItem(2L, "same");
    final AggregateTable<Record, String, Item> byName =
        AggregateTable.of(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_NAME, Item::name, FIXTURE_ITEM.ITEM_ID);

    assertThatThrownBy(
            () -> runInUseCase(() -> writer.update(byName, new Item(1L, "same", 1L), set -> {})))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("matched 2 rows");
  }

  @ParameterizedTest(name = "expectedLockNo={0}")
  @ValueSource(longs = {0L, -1L})
  @DisplayName("期待する版が 1 未満なら、SQL を実行する前に IllegalArgumentException にする")
  void expectedLockNoBelowOneIsRejected(final long expectedLockNo) {
    insertItem(1L, "before");

    assertThatThrownBy(() -> runInUseCase(() -> updateItem(1L, expectedLockNo, "after")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedLockNo=" + expectedLockNo);
  }

  @Test
  @DisplayName("共通カラムのないテーブルは、集約の宣言で IllegalArgumentException にする")
  void tableWithoutCommonColumnsIsRejected() {
    assertThatThrownBy(() ->
            AggregateTable.<Record, Long, Item>of(
                NO_LOCK_TABLE, FIXTURE_ITEM.ITEM_ID, Item::id, FIXTURE_ITEM.ITEM_ID))
        .as("集約")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
    assertThatThrownBy(
            () -> ChildTable.<Record>of(NO_LOCK_TABLE, FIXTURE_ITEM.ITEM_ID, FIXTURE_ITEM.ITEM_ID))
        .as("子")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
  }

  @Test
  @DisplayName("自然キーに、保存済みの値と equals で比べられない型の列は使えない")
  void incomparableNaturalKeyTypeIsRejected() {
    assertThatThrownBy(() ->
            ChildTable.<Record>of(
                FIXTURE_ITEM_DETAIL, FIXTURE_ITEM_DETAIL.ITEM_ID, FIXTURE_ITEM_DETAIL.CREATED_AT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("natural key column must be");
  }

  @Test
  @DisplayName("ColumnValues は共通カラムと、集約の宣言から書く列を拒否する")
  void commonColumnsAndDeclaredColumnsAreRejected() {
    final ColumnValues<Record> values =
        new ColumnValues<>(FIXTURE_ITEM, List.of(FIXTURE_ITEM.ITEM_ID));

    assertThatThrownBy(() -> values.set(FIXTURE_ITEM.LOCK_NO, 5L))
        .as("lock_no")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
    assertThatThrownBy(() -> values.set(FIXTURE_ITEM.CREATED_AT, NOW))
        .as("created_at")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=created_at");
    assertThatThrownBy(() -> values.set(FIXTURE_ITEM.UPDATED_BY, DSL.val("someone")))
        .as("updated_by の式")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=updated_by");
    assertThatThrownBy(() -> values.set(FIXTURE_ITEM.PATCHED_AT, NOW))
        .as("patched_at")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=patched_at");
    assertThatThrownBy(() -> values.set(FIXTURE_ITEM.ITEM_ID, 9L))
        .as("宣言から書く列")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=item_id");
    assertThatCode(() -> values.set(FIXTURE_ITEM.ITEM_NAME, "業務の列")).doesNotThrowAnyException();
  }

  @Test
  @DisplayName("子は差分で保存し、集約にない子を削除し、両方にある子を更新し、新しい子を追加する")
  void childrenAreSavedAsDifference() {
    insertItem(1L, "root");
    insertDetail(1L, 1, "one");
    insertDetail(1L, 2, "two");
    insertDetail(1L, 3, "three");
    final Map<Integer, String> aggregateChildren = Map.of(2, "two'", 4, "four");

    runInUseCase(() -> saveChildren(1L, aggregateChildren));

    assertThat(details(1L)).isEqualTo(aggregateChildren);
    assertThat(
            dsl.select(FIXTURE_ITEM_DETAIL.LOCK_NO)
                .from(FIXTURE_ITEM_DETAIL)
                .where(FIXTURE_ITEM_DETAIL.DETAIL_NO.eq(2))
                .fetchSingle(FIXTURE_ITEM_DETAIL.LOCK_NO))
        .as("更新した子の版")
        .isEqualTo(2L);
    assertThat(
            dsl.select(FIXTURE_ITEM_DETAIL.LOCK_NO, FIXTURE_ITEM_DETAIL.CREATED_PGM_CD)
                .from(FIXTURE_ITEM_DETAIL)
                .where(FIXTURE_ITEM_DETAIL.DETAIL_NO.eq(4))
                .fetchSingle())
        .as("追加した子の版と作成者")
        .satisfies(added -> {
          assertThat(added.value1()).isEqualTo(1L);
          assertThat(added.value2()).isEqualTo(PGM_CD);
        });
    assertThat(item(1L).get(FIXTURE_ITEM.LOCK_NO)).as("ルートの版").isEqualTo(2L);
  }

  @Test
  @DisplayName("集約の子が空なら、保存済みのすべての子を消す")
  void emptyChildrenDeleteEveryStoredChild() {
    insertItem(1L, "root");
    insertDetail(1L, 1, "one");
    insertDetail(1L, 2, "two");

    runInUseCase(() -> saveChildren(1L, Map.of()));

    assertThat(details(1L)).isEmpty();
  }

  @Test
  @DisplayName("他のルートの子は、差分の削除と更新の対象にならない")
  void childrenOfOtherRootsAreUntouched() {
    insertItem(1L, "one");
    insertItem(2L, "two");
    insertDetail(1L, 1, "mine");
    insertDetail(2L, 1, "other");

    runInUseCase(() -> saveChildren(1L, Map.of()));

    assertThat(details(1L)).isEmpty();
    assertThat(details(2L)).as("他のルートの子").isEqualTo(Map.of(1, "other"));
  }

  @Test
  @DisplayName("子を渡さない、同じ子を二度渡す呼び出しは SQL の前に IllegalArgumentException にする")
  void unmatchedChildRowsAreRejected() {
    insertItem(1L, "root");

    assertThatThrownBy(() -> runInUseCase(() -> writer.update(ITEM, itemRoot(1L, 1L), set -> {})))
        .as("子を渡さない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("child rows were not put");
    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.update(
                    ITEM,
                    itemRoot(1L, 1L),
                    set -> {},
                    detailRows(List.of()),
                    detailRows(List.of()))))
        .as("二度渡す")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("put twice");
  }

  @Test
  @DisplayName("自然キーが重複か null、行ごとに書く列が違う子の行は、作るときに IllegalArgumentException にする")
  void invalidChildRowsAreRejected() {
    assertThatThrownBy(() -> detailRows(List.of(detail(5, "a"), detail(5, "b"))))
        .as("自然キーの重複")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("natural key is duplicated");
    assertThatThrownBy(() ->
            DETAILS.rows(
                List.of(detail(1, "a"), detail(2, "b")),
                (d, set) -> {
                  set.set(FIXTURE_ITEM_DETAIL.DETAIL_NO, d.number());
                  if (d.number() == FIRST_DETAIL) {
                    set.set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, d.text());
                  }
                }))
        .as("行ごとに書く列が違う")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("child rows must set the same columns");
    assertThatThrownBy(() ->
            DETAILS.rows(
                List.of("x"), (text, set) -> set.set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, text)))
        .as("自然キーを書かない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("natural key must be a non-null value");
  }

  @Test
  @DisplayName("insert は子を持つ集約のルートと子を書き、lock_no を 1 にして共通カラムを書く")
  void insertWritesRootAndChildren() {
    runInUseCase(() ->
        writer.insert(
            ITEM,
            itemRoot(1L, 1L),
            set -> set.set(FIXTURE_ITEM.ITEM_NAME, "new"),
            detailRows(List.of(detail(1, "one"), detail(2, "two")))));

    final Record row = item(1L);
    assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("new");
    assertThat(row.get(FIXTURE_ITEM.LOCK_NO)).isEqualTo(1L);
    assertThat(row.get(FIXTURE_ITEM.CREATED_AT)).isEqualTo(NOW);
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD)).isEqualTo(PGM_CD);
    assertThat(details(1L)).isEqualTo(Map.of(1, "one", 2, "two"));
  }

  @Test
  @DisplayName("insert は子を宣言していない集約に子を渡す呼び出しと、子を宣言した集約に子を渡さない呼び出しを拒否する")
  void insertRequiresChildrenToMatchDeclaredChildren() {
    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.insert(
                    ITEM_ONLY,
                    itemRoot(1L, 1L),
                    set -> set.set(FIXTURE_ITEM.ITEM_NAME, "x"),
                    detailRows(List.of()))))
        .as("子を宣言していない集約に子")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("child is not declared by the aggregate");
    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.insert(ITEM, itemRoot(1L, 1L), set -> set.set(FIXTURE_ITEM.ITEM_NAME, "x"))))
        .as("子を宣言した集約に子を渡さない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("child rows were not put");
  }

  @Test
  @DisplayName("insert は子を宣言していない集約を、空の子で書く")
  void insertWritesRootOnlyAggregate() {
    runInUseCase(() ->
        writer.insert(ITEM_ONLY, itemRoot(1L, 1L), set -> set.set(FIXTURE_ITEM.ITEM_NAME, "only")));

    assertThat(item(1L).get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("only");
  }

  @Test
  @DisplayName("insert は一意制約の違反を、原因を付けず SQL と値を含まない UNIQUE の競合の例外にする")
  void insertDuplicateKeyConflicts() {
    insertItem(1L, "one");

    // 23505 の後のトランザクションは中断状態になるため、この後に SQL を実行しない。
    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.insert(
                    ITEM_ONLY,
                    itemRoot(1L, 1L),
                    set -> set.set(FIXTURE_ITEM.ITEM_NAME, "duplicate"))))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("unique key already exists")
        .hasMessageContaining("table=t_fixture_item")
        .satisfies(thrown -> assertConflictWithoutSql(thrown, ConflictException.Kind.UNIQUE));
  }

  @Test
  @DisplayName("insertIfAbsent は衝突がなければ、ルートと子を insert と同じく書いて true を返す")
  void insertIfAbsentWritesRootAndChildren() {
    final boolean inserted =
        callInUseCase(() ->
            writer.insertIfAbsent(
                ITEM,
                itemRoot(1L, 1L),
                List.of(FIXTURE_ITEM.ITEM_ID),
                set -> set.set(FIXTURE_ITEM.ITEM_NAME, "new"),
                detailRows(List.of(detail(1, "one")))));

    assertThat(inserted).isTrue();
    final Record row = item(1L);
    assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("new");
    assertThat(row.get(FIXTURE_ITEM.LOCK_NO)).isEqualTo(1L);
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD)).isEqualTo(PGM_CD);
    assertThat(details(1L)).isEqualTo(Map.of(1, "one"));
  }

  @Test
  @DisplayName("insertIfAbsent は衝突の対象の列に同じ値の行があれば、例外にせず false を返し、ルートも子も書かない")
  void insertIfAbsentSkipsConflictingRootAndChildren() {
    insertItem(1L, "first");

    final boolean inserted =
        callInUseCase(() ->
            writer.insertIfAbsent(
                ITEM,
                itemRoot(1L, 1L),
                List.of(FIXTURE_ITEM.ITEM_ID),
                set -> set.set(FIXTURE_ITEM.ITEM_NAME, "second"),
                detailRows(List.of(detail(1, "one")))));

    // 例外にならないので、トランザクションは中断されず、続けて読める。
    assertThat(inserted).isFalse();
    final Record row = item(1L);
    assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("first");
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD)).isEqualTo(SEED_PGM_CD);
    assertThat(details(1L)).as("入れなかったルートの子").isEmpty();
  }

  @Test
  @DisplayName("一意制約の違反ではない SQLSTATE（サブクエリが複数行を返す 21000）は、競合の例外にせずそのまま投げる")
  void cardinalityViolationIsNotConflict() {
    insertItem(1L, "one");
    insertItem(2L, "two");

    // 21000 の後のトランザクションは中断状態になるため、この後に SQL を実行しない。
    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.update(
                    ITEM_ONLY,
                    itemRoot(1L, 1L),
                    set ->
                        set.set(
                            FIXTURE_ITEM.ITEM_NAME,
                            DSL.field(DSL.select(FIXTURE_ITEM.ITEM_NAME).from(FIXTURE_ITEM))))))
        .isInstanceOf(DataAccessException.class)
        .isNotInstanceOf(ConflictException.class)
        .rootCause()
        .isInstanceOfSatisfying(
            SQLException.class, sql -> assertThat(sql.getSQLState()).isEqualTo("21000"));
  }

  @ParameterizedTest(name = "読み取り専用={0}、insert={1}")
  @CsvSource({"false, true", "false, false", "true, true", "true, false"})
  @DisplayName("トランザクションの外と、読み取り専用のトランザクションでは、書き込みを SQL の前に拒否する")
  void writesRequireReadWriteTransaction(final boolean readOnlyTransaction, final boolean insert) {
    final TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.setPropagationBehavior(
        readOnlyTransaction
            ? TransactionDefinition.PROPAGATION_REQUIRES_NEW
            : TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
    template.setReadOnly(readOnlyTransaction);
    final Consumer<ColumnValues<Record>> rootColumns = set -> set.set(FIXTURE_ITEM.ITEM_NAME, "x");

    assertThatThrownBy(() ->
            template.executeWithoutResult(status ->
                runInUseCase(() -> {
                  if (insert) {
                    writer.insert(ITEM_ONLY, itemRoot(1L, 1L), rootColumns);
                  } else {
                    writer.update(ITEM_ONLY, itemRoot(1L, 1L), rootColumns);
                  }
                })))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("read-write transaction");
  }

  @Test
  @DisplayName("子の自然キーが一意制約で裏づけられず、残す子の更新が 2 行に合えば IllegalStateException を投げる")
  void childNaturalKeyMatchingTwoRowsIsRejected() {
    final ChildTable<Record> byText =
        ChildTable.of(
            FIXTURE_ITEM_DETAIL, FIXTURE_ITEM_DETAIL.ITEM_ID, FIXTURE_ITEM_DETAIL.DETAIL_TEXT);
    final AggregateTable<Record, Long, Item> aggregate =
        AggregateTable.of(
            FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID, Item::id, FIXTURE_ITEM.ITEM_ID, byText);
    insertItem(1L, "root");
    insertDetail(1L, 1, "same");
    insertDetail(1L, 2, "same");

    assertThatThrownBy(() ->
            runInUseCase(() ->
                writer.update(
                    aggregate,
                    itemRoot(1L, 1L),
                    set -> {},
                    byText.rows(
                        List.of("same"),
                        (text, set) -> set.set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, text)))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("child natural key matched 2 rows");
  }

  /**
   * 投げられた競合の例外が、種類を持ち、原因を持たず、ログに記録される文字列に SQL と入力値を含まないことを確かめる。
   *
   * <p>{@code ApiExceptionHandler} は例外を {@code setCause} で記録し、OTLP の {@code exception.stacktrace}
   * には {@link Throwable#printStackTrace} と同じ文字列が入る。
   */
  private static void assertConflictWithoutSql(
      final Throwable thrown, final ConflictException.Kind kind) {
    assertThat(thrown)
        .isInstanceOfSatisfying(
            ConflictException.class, conflict -> assertThat(conflict.kind()).isEqualTo(kind))
        .hasNoCause();
    final StringWriter stackTrace = new StringWriter();
    thrown.printStackTrace(new PrintWriter(stackTrace));
    assertThat(stackTrace.toString())
        .as("記録される stack trace")
        .doesNotContain("Caused by", "Key (", "SQL [", "ERROR:")
        .doesNotContainPattern(SQL_STATEMENT);
  }

  /** 子の集合の差分を、ルートの更新と一緒に保存する。 */
  private void saveChildren(final long itemId, final Map<Integer, String> children) {
    save(
        itemId,
        1L,
        set -> set.set(FIXTURE_ITEM.ITEM_NAME, "root"),
        children.entrySet().stream().map(e -> detail(e.getKey(), e.getValue())).toList());
  }

  /** 子の行を渡して、ルートを更新する。 */
  private void save(
      final long itemId,
      final long expectedLockNo,
      final Consumer<ColumnValues<Record>> rootColumns,
      final List<Detail> details) {
    writer.update(ITEM, itemRoot(itemId, expectedLockNo), rootColumns, detailRows(details));
  }

  /** 集約ルートの行を、版を比べて更新する。 */
  private void updateItem(final long itemId, final long expectedLockNo, final String name) {
    save(itemId, expectedLockNo, set -> set.set(FIXTURE_ITEM.ITEM_NAME, name), List.of());
  }

  /** {@code lock_no = 1} の行を作る。 */
  private void insertItem(final long itemId, final String name) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(() ->
            dsl.insertInto(FIXTURE_ITEM)
                .set(seedColumns.forInsert(FIXTURE_ITEM))
                .set(FIXTURE_ITEM.ITEM_ID, itemId)
                .set(FIXTURE_ITEM.ITEM_NAME, name)
                .execute());
  }

  /** {@code lock_no = 1} の子の行を作る。 */
  private void insertDetail(final long itemId, final int detailNo, final String text) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(() ->
            dsl.insertInto(FIXTURE_ITEM_DETAIL)
                .set(seedColumns.forInsert(FIXTURE_ITEM_DETAIL))
                .set(FIXTURE_ITEM_DETAIL.ITEM_ID, itemId)
                .set(FIXTURE_ITEM_DETAIL.DETAIL_NO, detailNo)
                .set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, text)
                .execute());
  }

  /** 別の人が先に更新した状態にする（版を 2 にする）。 */
  private void makeStale(final long itemId) {
    dsl.update(FIXTURE_ITEM)
        .set(FIXTURE_ITEM.LOCK_NO, FIXTURE_ITEM.LOCK_NO.plus(1))
        .where(FIXTURE_ITEM.ITEM_ID.eq(itemId))
        .execute();
  }

  private Record item(final long itemId) {
    return dsl.selectFrom(FIXTURE_ITEM).where(FIXTURE_ITEM.ITEM_ID.eq(itemId)).fetchSingle();
  }

  private Map<Integer, String> details(final long itemId) {
    return dsl.select(FIXTURE_ITEM_DETAIL.DETAIL_NO, FIXTURE_ITEM_DETAIL.DETAIL_TEXT)
        .from(FIXTURE_ITEM_DETAIL)
        .where(FIXTURE_ITEM_DETAIL.ITEM_ID.eq(itemId))
        .fetchMap(FIXTURE_ITEM_DETAIL.DETAIL_NO, FIXTURE_ITEM_DETAIL.DETAIL_TEXT);
  }

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して戻り値のある処理を実行する。 */
  private static <T> T callInUseCase(final Supplier<T> call) {
    return ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(call::get);
  }

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して戻り値のない処理を実行する。 */
  private static void runInUseCase(final Runnable call) {
    ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).run(call);
  }
}
