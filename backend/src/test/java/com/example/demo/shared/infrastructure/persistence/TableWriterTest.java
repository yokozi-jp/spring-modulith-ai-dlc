package com.example.demo.shared.infrastructure.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.concurrency.ConflictException;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Supplier;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

/** {@link TableWriter} の SQL と件数の判定を、テスト専用のテーブルと実 PostgreSQL で確かめる。各テストはロールバックする。 */
// 入口ごとの成功と失敗の条件をテストに分けるため、メソッドの数の上限を外す。
// 行の値はテストごとに読み下せるよう、定数にせず文字列のまま書く。
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class TableWriterTest {

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

  /** lock_no を持たないテーブル。 */
  private static final Table<Record> NO_LOCK_TABLE = DSL.table(DSL.name("t_no_lock"));

  /** アプリロールで接続した jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 行を作るときの共通カラムの値。 */
  private CommonColumns seedColumns;

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
  @DisplayName("updateCheckingVersion は業務の列と updated_* を書き、版を期待値 + 1 にし、created_* を変えない")
  void updateCheckingVersionUpdatesOneRow() {
    insertItem(1L, "before");

    inUseCase(() -> updateItem(1L, 1L, "after"));

    final Record row = item(1L);
    assertThat(row.get(FIXTURE_ITEM.ITEM_NAME)).as("業務の列").isEqualTo("after");
    assertThat(row.get(FIXTURE_ITEM.LOCK_NO)).as("版").isEqualTo(2L);
    assertThat(row.get(FIXTURE_ITEM.UPDATED_AT)).as("updated_at").isEqualTo(NOW);
    assertThat(row.get(FIXTURE_ITEM.UPDATED_PGM_CD)).as("updated_pgm_cd").isEqualTo(PGM_CD);
    assertThat(row.get(FIXTURE_ITEM.CREATED_AT)).as("created_at").isEqualTo(CREATED);
    assertThat(row.get(FIXTURE_ITEM.CREATED_PGM_CD)).as("created_pgm_cd").isEqualTo(SEED_PGM_CD);
  }

  @Test
  @DisplayName("updateCheckingVersion は業務の列がなくても、版と updated_* を進める")
  void updateCheckingVersionWithoutBusinessColumnsAdvancesVersion() {
    insertItem(1L, "before");

    inUseCase(
        () ->
            writer.updateCheckingVersion(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(1L), 1L, set -> {}));

    assertThat(item(1L).get(FIXTURE_ITEM.LOCK_NO)).isEqualTo(2L);
    assertThat(item(1L).get(FIXTURE_ITEM.UPDATED_AT)).isEqualTo(NOW);
  }

  @Test
  @DisplayName("updateCheckingVersion は版が違えば、原因なしの競合の例外を投げ、行を変えない")
  void updateCheckingVersionWithStaleVersionConflicts() {
    insertItem(1L, "before");
    makeStale(1L);

    assertThatThrownBy(() -> inUseCase(() -> updateItem(1L, 1L, "after")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("table=t_fixture_item")
        .hasMessageContaining("key=[1]")
        .hasMessageContaining("expectedLockNo=1")
        .hasNoCause();
    assertThat(item(1L).get(FIXTURE_ITEM.ITEM_NAME)).isEqualTo("before");
  }

  @Test
  @DisplayName("updateCheckingVersion は主キーの行がなければ NoSuchElementException を投げる")
  void updateCheckingVersionWithoutRowIsNotFound() {
    assertThatThrownBy(() -> inUseCase(() -> updateItem(1L, 1L, "after")))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessageContaining("table=t_fixture_item");
  }

  @Test
  @DisplayName("updateCheckingVersion は主キーの条件が 2 行に合えば IllegalStateException を投げる")
  void updateCheckingVersionMatchingTwoRowsIsRejected() {
    insertItem(1L, "one");
    insertItem(2L, "two");

    assertThatThrownBy(
            () ->
                inUseCase(
                    () ->
                        writer.updateCheckingVersion(
                            FIXTURE_ITEM,
                            FIXTURE_ITEM.ITEM_ID.in(1L, 2L),
                            1L,
                            set -> set.set(FIXTURE_ITEM.ITEM_NAME, "x"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("matched 2 rows");
  }

  @Test
  @DisplayName("updateChild は子の行が 1 行でなければ IllegalStateException を投げる")
  void updateChildMatchingNoRowIsRejected() {
    insertItem(1L, "root");

    assertThatThrownBy(
            () ->
                runInUseCase(
                    () ->
                        updateItem(1L, 1L, "root")
                            .updateChild(
                                FIXTURE_ITEM_DETAIL,
                                FIXTURE_ITEM_DETAIL
                                    .ITEM_ID
                                    .eq(1L)
                                    .and(FIXTURE_ITEM_DETAIL.DETAIL_NO.eq(9)),
                                set -> set.set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, "x"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("matched 0 rows")
        .hasMessageContaining("table=t_fixture_item_detail");
  }

  @Test
  @DisplayName("lock_no のないテーブルは、SQL を実行する前に IllegalArgumentException にする")
  void tableWithoutLockNoIsRejected() {
    final Condition anyRow = DSL.trueCondition();

    assertThatThrownBy(() -> writer.updateCheckingVersion(NO_LOCK_TABLE, anyRow, 1L, set -> {}))
        .as("updateCheckingVersion")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
    assertThatThrownBy(() -> writer.deleteCheckingVersion(NO_LOCK_TABLE, anyRow, 1L))
        .as("deleteCheckingVersion")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
    assertThatThrownBy(() -> inUseCase(() -> writer.updateWhere(NO_LOCK_TABLE, anyRow, set -> {})))
        .as("updateWhere")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column=lock_no");
  }

  @ParameterizedTest(name = "expectedLockNo={0}")
  @ValueSource(longs = {0L, -1L})
  @DisplayName("期待する版が 1 未満なら、更新でも削除でも IllegalArgumentException にする")
  void expectedLockNoBelowOneIsRejected(final long expectedLockNo) {
    insertItem(1L, "before");

    assertThatThrownBy(() -> inUseCase(() -> updateItem(1L, expectedLockNo, "after")))
        .as("updateCheckingVersion")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedLockNo=" + expectedLockNo);
    assertThatThrownBy(
            () ->
                writer.deleteCheckingVersion(
                    FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(1L), expectedLockNo))
        .as("deleteCheckingVersion")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedLockNo=" + expectedLockNo);
  }

  @Test
  @DisplayName("updateWhere は条件に合うすべての行の版を 1 進め、件数を返す")
  void updateWhereAdvancesEveryMatchedVersion() {
    insertItem(1L, "one");
    insertItem(2L, "two");
    makeStale(2L);

    final int updated =
        inUseCase(
            () ->
                writer.updateWhere(
                    FIXTURE_ITEM,
                    FIXTURE_ITEM.ITEM_ID.in(1L, 2L),
                    set -> set.set(FIXTURE_ITEM.ITEM_NAME, "bulk")));
    final int none =
        inUseCase(
            () ->
                writer.updateWhere(
                    FIXTURE_ITEM,
                    FIXTURE_ITEM.ITEM_ID.eq(3L),
                    set -> set.set(FIXTURE_ITEM.ITEM_NAME, "none")));

    assertThat(updated).as("件数").isEqualTo(2);
    assertThat(none).as("合う行がない").isZero();
    assertThat(item(1L).get(FIXTURE_ITEM.LOCK_NO)).as("1 の版").isEqualTo(2L);
    assertThat(item(2L).get(FIXTURE_ITEM.LOCK_NO)).as("2 の版").isEqualTo(3L);
    assertThat(item(2L).get(FIXTURE_ITEM.ITEM_NAME)).as("業務の列").isEqualTo("bulk");
  }

  @Test
  @DisplayName("deleteWhere は条件に合う行を削除し、件数を返す")
  void deleteWhereReturnsCount() {
    insertItem(1L, "one");
    insertItem(2L, "two");

    final int deleted = writer.deleteWhere(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.in(1L, 2L, 3L));

    assertThat(deleted).isEqualTo(2);
    assertThat(dsl.fetchCount(FIXTURE_ITEM)).isZero();
  }

  @Test
  @DisplayName("ColumnValues は共通カラムを拒否し、updateWhere は業務の列がなければ拒否する")
  void commonColumnsAndEmptyValuesAreRejected() {
    final ColumnValues<Record> values = new ColumnValues<>(FIXTURE_ITEM);

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
    assertThatThrownBy(
            () -> inUseCase(() -> writer.updateWhere(FIXTURE_ITEM, DSL.trueCondition(), set -> {})))
        .as("業務の列がない updateWhere")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no business column");
  }

  @Test
  @DisplayName("子の差分は、ルートの更新、集約にない子の削除、新しい子の追加、両方にある子の更新の順で保存する")
  void childrenAreSavedAsDifference() {
    insertItem(1L, "root");
    insertDetail(1L, 1, "one");
    insertDetail(1L, 2, "two");
    insertDetail(1L, 3, "three");
    final Map<Integer, String> aggregateChildren = Map.of(2, "two'", 4, "four");

    inUseCase(() -> saveChildren(1L, aggregateChildren));

    assertThat(details(1L)).isEqualTo(aggregateChildren);
    assertThat(
            dsl.select(FIXTURE_ITEM_DETAIL.LOCK_NO)
                .from(FIXTURE_ITEM_DETAIL)
                .where(FIXTURE_ITEM_DETAIL.DETAIL_NO.eq(2))
                .fetchSingle(FIXTURE_ITEM_DETAIL.LOCK_NO))
        .as("更新した子の版")
        .isEqualTo(2L);
    assertThat(item(1L).get(FIXTURE_ITEM.LOCK_NO)).as("ルートの版").isEqualTo(2L);
  }

  @Test
  @DisplayName("集約の子が空なら、子の差分の削除はすべての子を消す")
  void emptyChildrenDeleteEveryStoredChild() {
    insertItem(1L, "root");
    insertDetail(1L, 1, "one");
    insertDetail(1L, 2, "two");

    inUseCase(() -> saveChildren(1L, Map.of()));

    assertThat(details(1L)).isEmpty();
  }

  @Test
  @DisplayName("deleteCheckingVersion は期待する版の行を削除し、DeletedRoot で子を削除する")
  void deleteCheckingVersionDeletesRootAndChildren() {
    insertItem(1L, "root");
    insertDetail(1L, 1, "one");

    writer
        .deleteCheckingVersion(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(1L), 1L)
        .deleteChildren(FIXTURE_ITEM_DETAIL, FIXTURE_ITEM_DETAIL.ITEM_ID.eq(1L));

    assertThat(dsl.fetchCount(FIXTURE_ITEM)).as("ルート").isZero();
    assertThat(dsl.fetchCount(FIXTURE_ITEM_DETAIL)).as("子").isZero();
  }

  @Test
  @DisplayName("deleteCheckingVersion は版が違えば競合の例外を、行がなければ NoSuchElementException を投げる")
  void deleteCheckingVersionRejectsStaleOrMissingRow() {
    insertItem(1L, "root");
    makeStale(1L);

    assertThatThrownBy(
            () -> writer.deleteCheckingVersion(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(1L), 1L))
        .as("版の違い")
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("table=t_fixture_item")
        .hasNoCause();
    assertThatThrownBy(
            () -> writer.deleteCheckingVersion(FIXTURE_ITEM, FIXTURE_ITEM.ITEM_ID.eq(9L), 1L))
        .as("行がない")
        .isInstanceOf(NoSuchElementException.class);
    assertThat(dsl.fetchCount(FIXTURE_ITEM)).as("行は残る").isOne();
  }

  /** 子の集合の差分を、規約の手順の順で保存する。 */
  private LockedRoot saveChildren(final long itemId, final Map<Integer, String> children) {
    final LockedRoot root = updateItem(itemId, 1L, "root");
    final Set<Integer> stored =
        new HashSet<>(
            dsl.select(FIXTURE_ITEM_DETAIL.DETAIL_NO)
                .from(FIXTURE_ITEM_DETAIL)
                .where(FIXTURE_ITEM_DETAIL.ITEM_ID.eq(itemId))
                .fetch(FIXTURE_ITEM_DETAIL.DETAIL_NO));
    final List<Integer> kept = List.copyOf(children.keySet());
    root.deleteChildren(
        FIXTURE_ITEM_DETAIL,
        FIXTURE_ITEM_DETAIL.ITEM_ID.eq(itemId).and(FIXTURE_ITEM_DETAIL.DETAIL_NO.notIn(kept)));
    children.forEach(
        (detailNo, text) -> {
          if (stored.contains(detailNo)) {
            root.updateChild(
                FIXTURE_ITEM_DETAIL,
                FIXTURE_ITEM_DETAIL
                    .ITEM_ID
                    .eq(itemId)
                    .and(FIXTURE_ITEM_DETAIL.DETAIL_NO.eq(detailNo)),
                set -> set.set(FIXTURE_ITEM_DETAIL.DETAIL_TEXT, text));
          } else {
            insertDetail(itemId, detailNo, text);
          }
        });
    return root;
  }

  /** 集約ルートの行を版を比べて更新する。 */
  private LockedRoot updateItem(final long itemId, final long expectedLockNo, final String name) {
    return writer.updateCheckingVersion(
        FIXTURE_ITEM,
        FIXTURE_ITEM.ITEM_ID.eq(itemId),
        expectedLockNo,
        set -> set.set(FIXTURE_ITEM.ITEM_NAME, name));
  }

  /** {@code lock_no = 1} の行を作る。 */
  private void insertItem(final long itemId, final String name) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(
            () ->
                dsl.insertInto(FIXTURE_ITEM)
                    .set(seedColumns.forInsert(FIXTURE_ITEM))
                    .set(FIXTURE_ITEM.ITEM_ID, itemId)
                    .set(FIXTURE_ITEM.ITEM_NAME, name)
                    .execute());
  }

  /** {@code lock_no = 1} の子の行を作る。 */
  private void insertDetail(final long itemId, final int detailNo, final String text) {
    ScopedValue.where(PgmCdAspect.PGM_CD, SEED_PGM_CD)
        .run(
            () ->
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

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して実行する。 */
  private static <T> T inUseCase(final Supplier<T> call) {
    return ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).call(call::get);
  }

  /** ユースケースの呼び出しの中として、pgm_cd を束縛して戻り値のない処理を実行する。 */
  private static void runInUseCase(final Runnable call) {
    ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD).run(call);
  }
}
