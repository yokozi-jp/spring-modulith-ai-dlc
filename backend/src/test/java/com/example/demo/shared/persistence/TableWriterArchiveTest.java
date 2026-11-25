package com.example.demo.shared.persistence;

import static com.example.demo.jooq.tables.FixtureItemDetailArchiveTable.FIXTURE_ITEM_DETAIL_ARCHIVE;
import static com.example.demo.jooq.tables.FixtureItemDetailTable.FIXTURE_ITEM_DETAIL;
import static com.example.demo.jooq.tables.FixtureItemTable.FIXTURE_ITEM;
import static com.example.demo.shared.persistence.FixtureAggregates.ITEM;
import static com.example.demo.shared.persistence.FixtureAggregates.detail;
import static com.example.demo.shared.persistence.FixtureAggregates.detailRows;
import static com.example.demo.shared.persistence.FixtureAggregates.itemRoot;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.demo.shared.persistence.FixtureAggregates.Detail;
import com.example.demo.shared.persistence.FixtureAggregates.Item;
import com.example.demo.testkit.DatabaseTest;
import com.example.demo.testkit.FixtureTablesExtension;
import com.example.demo.testkit.TracerStubs;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/** 履歴の表を宣言した子の削除が、削除前の行と削除の共通カラムを同じ呼び出しで残すことを、実 PostgreSQL で確かめる。各テストはロールバックする。 */
// 履歴の表の列を static import で読み、場合ごとにテストを分け、同じ文言の子の行を何度も作るため、PMD の上限を外す。
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyStaticImports"})
@DatabaseTest
@ExtendWith(FixtureTablesExtension.class)
class TableWriterArchiveTest {

  /** 削除の時刻。 */
  private static final Instant NOW = Instant.parse("2026-10-04T01:02:03.123456Z");

  /** 行を作った時刻。 */
  private static final Instant CREATED = Instant.parse("2026-10-01T00:00:00.000001Z");

  /** 削除のユースケースとして束縛する pgm_cd。 */
  private static final String PGM_CD = "shared.TableWriterArchiveTest";

  /** 現在のスパンの trace ID。 */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** アプリロールで接続した jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 行を作る。 */
  private FixtureSeeds seeds;

  /** 検証対象。 */
  private TableWriter writer;

  @BeforeEach
  void setUp() {
    seeds =
        new FixtureSeeds(
            dsl,
            new CommonColumns(
                Clock.fixed(CREATED, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID)));
    writer =
        new TableWriter(
            dsl,
            new CommonColumns(Clock.fixed(NOW, ZoneOffset.UTC), TracerStubs.withTraceId(TRACE_ID)));
  }

  @Test
  @DisplayName("なくなった子は、業務の列と作成と更新の共通カラムを削除前のまま履歴の表に写し、退避のカラムに削除した日時、機能、trace を登録する")
  void removedChildrenAreCopiedBeforeTheyAreDeleted() {
    seeds.insertItem(1L, "root");
    seeds.insertDetail(1L, 1, "one");
    seeds.insertDetail(1L, 2, "two");
    seeds.insertDetail(1L, 3, "three");

    save(1L, List.of(detail(2, "two"), detail(3, "three")));

    assertThat(dsl.fetchCount(FIXTURE_ITEM_DETAIL)).as("残る子").isEqualTo(2);
    final Record archived = dsl.selectFrom(FIXTURE_ITEM_DETAIL_ARCHIVE).fetchSingle();
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.ITEM_ID)).isEqualTo(1L);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_NO)).isEqualTo(1);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT)).isEqualTo("one");
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.CREATED_AT))
        .as("作成日時は写す")
        .isEqualTo(CREATED);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.CREATED_PGM_CD))
        .isEqualTo(FixtureSeeds.SEED_PGM_CD);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.LOCK_NO)).isEqualTo(1L);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.ARCHIVED_AT)).isEqualTo(NOW);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.ARCHIVED_BY)).isEqualTo(PGM_CD);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.ARCHIVED_PGM_CD)).isEqualTo(PGM_CD);
    assertThat(archived.get(FIXTURE_ITEM_DETAIL_ARCHIVE.ARCHIVED_TX_ID)).isEqualTo(TRACE_ID);
  }

  @Test
  @DisplayName("子が空なら、保存済みのすべての子を履歴の表に写して消す")
  void emptyChildrenArchiveEveryStoredChild() {
    seeds.insertItem(1L, "root");
    seeds.insertDetail(1L, 1, "one");
    seeds.insertDetail(1L, 2, "two");

    save(1L, List.of());

    assertThat(dsl.fetchCount(FIXTURE_ITEM_DETAIL)).isZero();
    assertThat(
            dsl.select(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT)
                .from(FIXTURE_ITEM_DETAIL_ARCHIVE)
                .orderBy(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_NO)
                .fetch(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT))
        .containsExactly("one", "two");
  }

  @Test
  @DisplayName("消える子がなければ履歴の表に書かず、ほかのルートの子も写さない")
  void nothingIsArchivedWithoutRemovedChildren() {
    seeds.insertItem(1L, "root");
    seeds.insertItem(2L, "root");
    seeds.insertDetail(1L, 1, "one");
    seeds.insertDetail(2L, 1, "other");

    save(1L, List.of(detail(1, "one'")));

    assertThat(dsl.fetchCount(FIXTURE_ITEM_DETAIL_ARCHIVE)).isZero();
    assertThat(dsl.fetchCount(FIXTURE_ITEM_DETAIL)).isEqualTo(2);
  }

  @Test
  @DisplayName("同じ自然キーの子を作り直して消しても、履歴の表の行は一意制約に当たらず、削除のたびに残る")
  void recreatedChildIsArchivedEachTime() {
    seeds.insertItem(1L, "root");
    seeds.insertDetail(1L, 1, "one");

    save(1L, 1L, List.of());
    seeds.insertDetail(1L, 1, "again");
    save(1L, 2L, List.of());

    assertThat(
            dsl.select(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT)
                .from(FIXTURE_ITEM_DETAIL_ARCHIVE)
                .orderBy(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT)
                .fetch(FIXTURE_ITEM_DETAIL_ARCHIVE.DETAIL_TEXT))
        .containsExactly("again", "one");
  }

  @Test
  @DisplayName("履歴の表に子の列か退避のカラムがなければ、宣言のときに IllegalArgumentException にする")
  void archiveWithoutRequiredColumnsIsRejected() {
    assertThatThrownBy(() ->
            ChildTable.of(
                FIXTURE_ITEM_DETAIL,
                FIXTURE_ITEM_DETAIL.ITEM_ID,
                FIXTURE_ITEM_DETAIL,
                FIXTURE_ITEM_DETAIL.DETAIL_NO))
        .as("退避のカラムがない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("archived_at");
    assertThatThrownBy(() ->
            ChildTable.of(
                FIXTURE_ITEM_DETAIL,
                FIXTURE_ITEM_DETAIL.ITEM_ID,
                DSL.table(DSL.name("h_empty")),
                FIXTURE_ITEM_DETAIL.DETAIL_NO))
        .as("子の列がない")
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("column is missing or has an unexpected type");
  }

  /** 子の全行を渡して、ルートを更新する。 */
  private void save(final long itemId, final List<Detail> details) {
    save(itemId, 1L, details);
  }

  /** 読んだ版を指定して、子の全行を渡し、ルートを更新する。 */
  private void save(final long itemId, final long lockNo, final List<Detail> details) {
    save(ITEM, itemId, lockNo, detailRows(details));
  }

  /** 集約と子の行を指定して、ルートを更新する。 */
  private void save(
      final AggregateTable<Record, Long, Item> aggregate,
      final long itemId,
      final long lockNo,
      final ChildRows<Record> rows) {
    ScopedValue.where(PgmCdAspect.PGM_CD, PGM_CD)
        .run(() ->
            writer.update(
                aggregate,
                itemRoot(itemId, lockNo),
                set -> set.set(FIXTURE_ITEM.ITEM_NAME, "root"),
                rows));
  }
}
