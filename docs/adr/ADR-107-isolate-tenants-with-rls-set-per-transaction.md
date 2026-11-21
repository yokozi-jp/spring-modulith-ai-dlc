---
type: ADR
title: 'ADR-107: 業務の表を RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する'
description: 業務の表に tenant_public_id を持たせ、既定値をトランザクションのテナントにし、ENABLE と FORCE の RLS と、missing_ok を使わない TO PUBLIC のポリシーでテナントを分ける決定。テナントはトランザクションマネージャーが TenantContext から読み、モジュールのロールと同じ SQL でトランザクションの間だけ設定し、テナントを束縛しない業務のモジュールのトランザクションは始めない。RLS の外に置くのは modulith と tenancy のスキーマの表だけにし、業務の一意索引は tenant_public_id から始め、全テナントの行はテナントを 1 つずつ設定して書き換える。
tags: [adr, database, postgresql, security, tenancy, rls]
---

# ADR-107: 業務の表を RLS でテナントごとに分け、テナントをロールと同じ SQL でトランザクションごとに設定する

## Status

Proposed

## Date

2026-11-21

## Context

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、業務のデータを**テナント**（サービスを契約する単位の組織）ごとに分ける。
今の業務の表はテナントの列を持たない。
別のテナントの商品、注文、決済が見えないことを、アプリのコードの問い合わせの条件だけで守っている。
1 つの問い合わせの条件の漏れが、そのまま別の組織のデータの漏れになる。

テナントの列と RLS を入れる時期は [issue #306](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/306) で調べた。
調査の初めの結論は「今は入れない」だったが、利用者の方針で「今入れる」に改めた。
見送りの理由はどれも「後から入れても費用が大きくない」ことで、今入れない理由にならないためである。
テナントが 1 つの間に入れておけば、新しい表、入口、テストが最初からテナントを前提に作られる。
形は [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363) の grilling（Q1 から Q4、Q10、Q24、Q26）で決め、#369 の「スキーマと RLS」と「トランザクションの準備」にまとめた。

この判断は、次の決定を前提にする。

- トランザクションマネージャーが、トランザクションを始めた直後に `set_config('role', ?, true)` でモジュールのロールへ切り替える（[ADR-094](ADR-094-switch-to-module-database-roles.md)、[issue #307](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/307)）。
- 操作しているテナントの `public_id` は、リクエストとリスナーの入口が `TenantContext` に束縛し、トランザクションが始まる時点で読める（[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)）。
- テナント、所属、役割は `tenancy` のスキーマに持ち、テナントが決まる前に引く（[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)、[issue #316](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/316)）。
- マイグレーション用のロールは superuser でも `BYPASSRLS` でもない（[issue #370](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/370)）。

PostgreSQL の RLS と設定の関数には、次の性質がある。

- superuser と `BYPASSRLS` のロールは、常にポリシーを受けない。表の所有者も、`FORCE ROW LEVEL SECURITY` を付けない限りポリシーを受けない。
- ロールを指定しないか `PUBLIC` を指定したポリシーは、すべてのロールに効く。`USING` だけを書いたポリシーは、同じ式で INSERT と UPDATE の新しい行も確かめる。
- `current_setting` は、`missing_ok` を真にしない限り、ない設定を読むとエラーになる。
- 列の既定値の式は、その列に値を書かない INSERT のたびに評価される。
- `set_config` の `is_local` を真にすると、設定はそのトランザクションの間だけ効く。
- 一意性、主キー、外部キーの検査は、常に RLS を受けない。検査の結果から、見えない行の有無が分かる。
- `TRUNCATE` は RLS を受けない。

実装（#377）で、Decision に `tenant_public_id` の既定値を足した。
Repository と seed の INSERT がテナントの列を書かずに済み、書き忘れる余地がなくなるためである。
設定の SQL も、設定するものの `set_config` だけを並べる形に改めた。
ロールとテナントは、トランザクションによってはどちらか、または両方がなく（Spring Modulith の内部、再投入、ShedLock）、テストの補助とシーダーは片方だけを本番と同じ SQL で設定するためである。
設定の名前は SQL に固定し、値だけをバインド変数で渡す。
任意の設定の名前を受ける入口を、アプリにもテストにも作らないためである。

実装の後の監査で、テナントを束縛し忘れたトランザクションが、エラーにならず 0 件を返す経路が見つかった。
テナントがある間に作った汎用の実行計画は、接続の prepared statement に残り、テナントのない実行でも計画し直されない。
その計画が表を順に走査し、漏洩のない比較（`order_status_typ = $1` など）で全行を落とすと、RLS の式は一度も評価されない。
そこで Decision に、トランザクションマネージャーがテナントのない業務のトランザクションを始めないことを足し、ポリシーのエラーを 2 段目の防御に改めた。

## Decision

業務の表を、PostgreSQL の RLS でテナントごとに分ける。
業務の表の範囲は、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#rlsでテナントを分ける表)に示す。
今は `product.m_product`、`ordering.t_order`、`ordering.t_order_line`、`payment.t_payment` である。

- 業務の表は、テナントの `public_id` を `tenant_public_id`（uuid、NOT NULL）として持つ。テナントの主キーは持たない（[ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)）。
- `tenant_public_id` の既定値は `current_setting('app.current_tenant')::uuid` にし、Repository と seed の INSERT はこの列を書かない。Repository のコードが列を書き忘れる余地がなく、テナントを設定しないトランザクションの INSERT は `current_setting` のエラーで失敗する。値を書いた INSERT も、別のテナントの値ならポリシーが拒否する。
- uuid の列に DEFAULT を付けない規則（[ADR-060](ADR-060-generate-uuid-primary-keys-in-application.md)）の例外は、この既定値だけにする。この既定値は ID を採番せず、トランザクションに設定したテナントを入れるだけだからである。例外の範囲は[PostgreSQLの主キー](../database/postgresql-primary-keys.md#uuidの採番)に示す。
- 業務の表に `ENABLE ROW LEVEL SECURITY` と `FORCE ROW LEVEL SECURITY` を付ける。`FORCE` で、表の所有者のマイグレーション用のロールにもポリシーを効かせる。
- ポリシーは `USING (tenant_public_id = current_setting('app.current_tenant')::uuid)` にし、`missing_ok` を使わない。テナントを設定しない読み書きは、RLS の式を評価した時点でエラーになる。ただし、式を評価するかは実行計画で決まるため、これは 2 段目の防御である。
- ポリシーの対象は `PUBLIC` にする。ポリシーは行だけを絞り、どのロールが表に触れるかは表の権限（ADR-094 のモジュールのロール）で絞る。
- 業務の一意索引は、すべて `tenant_public_id` から始める。先頭にテナントがない索引では、RLS を受けない一意性の検査が別のテナントの値と衝突し、INSERT の失敗からその値の有無が分かるためである。
- 同じモジュールの表どうしの参照の外部キーは、[ADR-108](ADR-108-add-tenant-composite-foreign-keys-within-modules.md) で決める。

テナントは、トランザクションごとに設定する。

- トランザクションマネージャーの、トランザクションを始めた直後に接続を準備する箇所（`prepareTransactionalConnection`）で、ロールとテナントを 1 つの SQL で設定する。
- どちらも `is_local` を真にして、トランザクションの間だけ効かせる。設定の名前（`role` と `app.current_tenant`）は SQL に固定し、値をバインド変数で渡す。
- テナントは `TenantContext.findCurrent()` で束縛から読む。束縛がないトランザクションでは、`app.current_tenant` を設定しない。モジュールが分からないトランザクションでは、ロールを設定しない（ADR-094）。SQL には、設定するものの `set_config` だけを並べ、どちらもなければ SQL を流さない。
- 業務の表を持つモジュール（`product`、`ordering`、`payment`）のトランザクションを、テナントを束縛せずに始めたら、SQL を流す前に `IllegalStateException` で失敗させる。テナントの設定忘れを確実に止めるのは、この検査である。モジュールの一覧は `ModuleRoles.TENANT_SCOPED_MODULES` に置き、RLS の外に置くスキーマを除いたモジュールと一致することをテストで確かめる。`tenancy` のトランザクションと、モジュールが分からないトランザクションは、テナントなしで始められる。
- 接続プールの初期化の SQL とセッション単位の設定には、テナントを書かない。

ロールとテナントの両方を設定するときの SQL は、次のとおりである。

```sql
SELECT set_config('role', ?, true), set_config('app.current_tenant', ?, true)
```

RLS の外に置く表は、`modulith` と `tenancy` のスキーマの表だけにする。

- `modulith` のスキーマの表（イベントの出版、その archive、ShedLock）は、Spring Modulith が決まった列だけを INSERT し、Staleness Monitor と再投入（[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）が全テナントの行を扱う。テナントは、イベントの本文が運ぶ（ADR-105）。
- `tenancy` のスキーマの表は、テナントが決まる前に引く。触れるのは `tenancy` のモジュールのロールだけである（ADR-094、ADR-101）。

全テナントの行を書き換える処理（データの移行、開発用の代表データの reset など）は、テナントを 1 つずつ設定して流す。
全テナントの行を読める専用のロールと、`BYPASSRLS` を持つアプリのロールは作らない。

本番にデータはないため、移行の手順は作らない。
業務の表は、changeset で目的の形に作り直す。

開発用の代表データ（seed）も、テナントを設定して本番と同じ経路で入れる。
商品 5 件、注文 6 件、決済 2 件の代表データを、`TenancySeeds` の 2 つのテナントの両方に入れる。
業務データが空かの判定と reset の削除は、`m_tenant` のテナントごとに、そのテナントを設定して行う。
テナントを設定しないまま業務の表を読まない。
これにより、[ADR-073](ADR-073-read-seeded-data-in-e2e.md) の代表データは、テナントごとの代表データになる。

規則の検査は、カタログのテスト、changeset の lint、DB のテストで行う。
検査の範囲は[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#rlsの検査)の「RLS の検査」に示す。

## Consequences

### Positive

- アプリのコードが問い合わせでテナントの条件を書き忘れても、別のテナントの行は読めず、書けない。
- テナントを束縛し忘れた業務のトランザクションは、SQL を流す前に失敗する。設定の漏れを「データがない」と取り違えない。ポリシーのエラーは、モジュールのロールへ直接切り替えたテストの補助やシーダーの誤りを止める 2 段目の防御になる。
- ロールとテナントを 1 か所の 1 つの SQL で、トランザクションの間だけ設定するため、プールの接続に残らない。
- 一意索引の先頭がテナントなので、別のテナントの値と衝突せず、INSERT の失敗から別のテナントの値が分からない。客先注文番号のような業務の値を、テナントごとに同じ値で持てる。
- Repository と seed の INSERT は `tenant_public_id` を書かず、列の書き忘れが起きない。

### Negative

- 業務の表を読み書きするトランザクションは、`TenantContext` の束縛の中で始める必要がある。束縛のない入口（バッチ、起動時の処理）から業務のモジュールのトランザクションを始めると、トランザクションマネージャーが拒否する。入口を足すときは、ADR-105 の束縛の許可先を先に変える。
- テストのフレームワークが始めるトランザクション（`@DatabaseTest` のロールバック用）は、テナントを束縛する前に始まる。テストの側に、そのトランザクションだけテナントを束縛して始めるトランザクションマネージャーの子クラスを置く。
- 全テナントの行を扱う処理は、テナントの設定を切り替えながら、同じ処理をテナントの数だけ繰り返す。
- PostgreSQL は、RLS を受けるロールの `COPY FROM` を受け付けない。`FORCE` で表の所有者もポリシーを受けるため、アプリ、モジュール、マイグレーション用のどのロールからも、業務の表へ `COPY FROM` で取り込めない。大量の行も INSERT で入れることになる。
- 業務の一意索引の 1 件ごとに、`tenant_public_id` の 16 バイトが増える。テナントの主キー（bigint）を持つ場合より 8 バイト多い（#363 の Q24）。
- INSERT の文に、行のテナントが現れない。どのテナントの行になるかは、トランザクションに設定したテナントで決まる。
- uuid の列に DEFAULT を付けない規則に、テナントの列の例外が 1 つ増え、カタログのテスト（規則 T13）がその既定値の式だけを除く。
- seed、テストのデータ、DB のテストは、テナントを設定して書く。テストの掃除は、RLS を受けない所有者の接続の `TRUNCATE` で行う。
- アプリのロールは、どの SQL からでも `set_config` でテナントを変えられる。DB で止められるのは誤りで、故意の迂回は止められない。ADR-094 の ArchUnit の規則（接続の設定の変更の禁止）で補う。

実装の後の監査で、次の限界を確かめた。
どれも今の形の限界として受け入れ、直していない。

- `is_local` を偽にした `app.current_tenant` の設定（セッション単位の `SET`）は、トランザクションの後もプールの接続に残る。トランザクションマネージャーを通らずにロールを切り替えた読み書きは、残ったテナントで動く。DB はこれを止められず、ArchUnit の `moduleCodeDoesNotChangeConnectionSettings` が、モジュールのコードから接続の設定を変えさせないことで守る。
- アプリのロールは `SET ROLE` で、どのモジュールのロールへも切り替えられる（ADR-094）。ポリシーはどのロールにも効くためテナントの境界は越えないが、モジュールの境界は越える。
- `EXPLAIN` の行数の見積もりと `pg_stat` の統計は、別のテナントの行も数える。表の行数の規模は、テナントをまたいで分かる。
- 主キーの IDENTITY は全テナントで 1 つの連番なので、値を指定した INSERT の一意性の違反から、別のテナントの行の主キーの有無が分かる。IDENTITY は `GENERATED ALWAYS` で、値を指定するには `OVERRIDING SYSTEM VALUE` が要り、jOOQ の API にはないため、生の SQL を書くコードだけがこの経路を使える。
- 業務の表の `tenant_public_id` は `tenancy.m_tenant` を外部キーで指さないため、`m_tenant` にないテナントの行も入る。開発用の代表データの reset は `m_tenant` のテナントだけを回るため、そうした行は消えずに残る。
- `modulith.event_publication` は RLS の外にあり、`TenantListenerAspect` はイベントの本文のテナントをそのまま束縛する。本文のテナントを偽った出版の行は、生の SQL を書くモジュールのコードだけが入れられる。

### Neutral

- `SET LOCAL` した設定がトランザクションの後に空文字列として残る挙動は、PostgreSQL の文書に書かれていない（#306 の調査で、別の実装のテストが観察した）。空文字列も `::uuid` の変換でエラーになるため、ポリシーは失敗する側に倒れる。この挙動は DB のテストで確かめる。
- テナントをまたいで操作する運用者は扱わない（[issue #367](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/367)）。
- `tenancy` の表どうしの参照の外部キーは、ADR-108 で決める。

## Alternatives Considered

### 選択肢1: テナントの列だけを入れ、アプリのコードの条件で分ける

- **Description**：業務の表に `tenant_public_id` を足し、RLS を付けず、Repository の問い合わせにテナントの条件を書く。
- **Pros**：RLS とポリシーの設定、トランザクションごとのテナントの設定が要らない。
- **Cons**：強制されない列は、条件を書き忘れても何も起きない。後で RLS を入れるときに、値が正しい保証にもならない（#306）。

### 選択肢2: テナントをセッション単位で設定する

- **Description**：接続プールの初期化の SQL か、接続を借りたときの `SET` でテナントを設定する。
- **Pros**：トランザクションの外の読み書きにもテナントが効く。
- **Cons**：初期化の SQL は物理的な接続ごとに 1 回しか動かず、リクエストごとに変わるテナントを表せない。`SET` は戻し忘れた接続にテナントが残り、次のリクエストに別のテナントが漏れる（ADR-094 の選択肢4 と同じ理由）。

### 選択肢3: ポリシーで `missing_ok` を使う

- **Description**：ポリシーを `current_setting('app.current_tenant', true)::uuid` で比べる。
- **Pros**：テナントを設定しないトランザクションでも、業務の表への問い合わせがエラーにならない。
- **Cons**：設定し忘れると値が NULL になり、比較が真にならないため 0 件を返す。設定の漏れを「データがない」と取り違える。

### 選択肢4: テナントごとにスキーマを分ける

- **Description**：テナントごとに `product`、`ordering`、`payment` のスキーマを作る。
- **Pros**：RLS のポリシーが要らず、テナントのデータをスキーマの単位で扱える。
- **Cons**：スキーマ名はモジュールの所有を表す固定値で、環境やテナントで変えない（[ADR-011](ADR-011-use-module-owned-database-schemas.md)、[PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md#分離方式)）。テナントを足すたびに、スキーマの適用をテナントの数だけ行うことになる。

### 選択肢5: ポリシーの対象をモジュールのロールに限る

- **Description**：ポリシーを `TO product_module` のように、表に触れるモジュールのロールにだけ掛ける。
- **Pros**：ポリシーが、表に触れてよいロールを文書の代わりに示す。
- **Cons**：触れるロールを表の権限とポリシーの 2 か所に書き、ロールを足すたびに両方を直す。ポリシーの対象にないロールは、テナントを設定しても行が見えない。マイグレーション用のロールも `FORCE` でポリシーを受けるため、テナントを 1 つずつ設定して全テナントの行を書き換えることができなくなる。

### 選択肢6: 全テナントを読める専用のロールを作る

- **Description**：`BYPASSRLS` か、全テナントを通すポリシーを持つロールを作り、全テナントの行を扱う処理に使う。
- **Pros**：全テナントの行を 1 つのトランザクションで扱える。
- **Cons**：イベントの出版は RLS の外の `modulith` の表にあり、Outbox を自前で持たないため、全テナントを読む処理がない（#363 の「取り込まないもの」）。このロールに切り替える誤りが、そのまま全テナントのデータの漏れになる。

### 選択肢7: Repository が `TenantContext` から `tenant_public_id` を書く

- **Description**：`tenant_public_id` に既定値を付けず、Repository と seed の INSERT が `TenantContext.current()` の値を書く。
- **Pros**：INSERT の文に行のテナントが現れる。uuid の列に DEFAULT を付けない規則に例外が要らない。
- **Cons**：すべての Repository と seed の INSERT に同じ代入が増える。列を書き忘れた INSERT は NOT NULL の違反で止まるが、書き忘れがないことを Repository ごとのテストで確かめることになる。

## References

- [issue #369: テナントでデータを分ける](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [issue #377: 業務の表に tenant_public_id と RLS を入れる](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/377)
- [issue #363](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/363)
- [issue #306](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/306)
- [ADR-060: UUID の採番をアプリケーションで行い、DB の DEFAULT で採番しない](ADR-060-generate-uuid-primary-keys-in-application.md)
- [ADR-073: E2E はシーダーの代表データを読むだけにし、変更するデータは各テストが作る](ADR-073-read-seeded-data-in-e2e.md)
- [ADR-089: 主キーをモジュールのスキーマと Repository の中に閉じ、外に出る値には例外なく public_id を使う](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-094: トランザクションの初めにモジュールの DB ロールへ切り替え、アプリロールを業務のスキーマから外す](ADR-094-switch-to-module-database-roles.md)
- [ADR-101: テナント、所属、役割をアプリの DB に持ち、テナントを選んだ時点で役割の権限を組み立てる](ADR-101-keep-tenants-and-roles-in-app-database.md)
- [ADR-105: 操作しているテナントを ScopedValue で束縛し、リクエストはセッションから、リスナーはイベントから取る](ADR-105-bind-tenant-to-request-and-event-context.md)
- [ADR-108: 同じモジュールの表どうしの参照に、テナントを含む複合の外部キーを作る](ADR-108-add-tenant-composite-foreign-keys-within-modules.md)
- [PostgreSQLのマルチテナント設計](../database/postgresql-multitenancy.md)
- [PostgreSQL Row Security Policies](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [PostgreSQL CREATE POLICY](https://www.postgresql.org/docs/current/sql-createpolicy.html)
- [PostgreSQL System Administration Functions](https://www.postgresql.org/docs/current/functions-admin.html)
- [PostgreSQL COPY](https://www.postgresql.org/docs/current/sql-copy.html)
