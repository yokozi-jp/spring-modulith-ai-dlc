---
type: Convention
title: PostgreSQLのマルチテナント設計
description: マルチテナントでテナントのデータを分離する方式の選び方と、このシステムのRLSの規則（RLSで分ける表とRLSの外に置く表、tenant_public_idとポリシー、トランザクションごとのテナントの設定、全テナントの行を書き換える処理、検査）、テナントごとのスキーマ差の禁止、テナントのシャーディングを定める規約。業務の表を足すとき、RLSのポリシーやテナントの設定を変えるとき、全テナントの行を書き換える処理を書くとき、テナント分離の方式を見直すときに読む。
tags: [convention, database, postgresql, multi-tenancy, rls, future-arch-guidelines]
---

# PostgreSQLのマルチテナント設計

業務の表はRow Level Security（RLS）でテナントごとに分け、`tenant_public_id`とトランザクションに設定したテナントを比べるポリシーで、別のテナントの行を読めず書けなくする。
テナントはモジュールのロールと同じSQLでトランザクションの間だけ設定し、設定しないトランザクションが業務の表に触れるとエラーになる。
全テナントの行を書き換える処理はテナントを1つずつ設定して流し、テナントごとにスキーマの差を作らない。

## 対象

この規約は、複数のテナントのデータを一つのシステムで扱う場合に適用する。
あるテナントが別のテナントのデータにアクセスできる状態は、どの方式でも許容しない。
規則の検査は「[RLSの検査](#rlsの検査)」に示す。

## 分離方式

- **インスタンス分離**：テナントごとにPostgreSQLのインスタンスを分ける。ノイジーネイバーを防げ、テナント単位でアップグレードやバックアップができるが、費用が最も高く、スキーマの適用とマスタの配信をテナントごとに行う必要がある。
- **DB分離**：一つのインスタンスの中でテナントごとにデータベースを分ける。リソースを平準化できるが、接続プールをテナント間で共有できず、`pg_stat_activity`をDBをまたいで参照できない。
- **RLS分離**：全テナントのデータを同じテーブルに置き、Row Level Securityでテナントの行だけを見せる。接続プールを共有でき、スキーマの変更も一度で済むが、ノイジーネイバーへの対策が弱い。

スキーマ名はモジュールの所有を表す固定値であり、[ADR-011](../adr/ADR-011-use-module-owned-database-schemas.md)のとおり環境やテナントで変えない。
そのため、テナントごとにスキーマを分ける方式は使わない。

方式は次の順で選ぶ。

1. 費用を許容でき、ノイジーネイバーを完全に排除する要件があれば、インスタンス分離にする。
2. テナント数が多い場合は、スキーマの適用にかかる手間を抑えるため、RLS分離にする。

このシステムはRLS分離を使う。
規則は次の節から示し、理由は[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)にある。

## RLSでテナントを分ける表

changesetが作るスキーマのうち、`modulith`と`tenancy`を除くスキーマの表を、すべて業務の表としてRLSでテナントごとに分ける。
今の業務の表は、`product.m_product`、`ordering.t_order`、`ordering.t_order_line`、`payment.t_payment`である。

- 業務の表は、テナントの`public_id`を`tenant_public_id`（`uuid`、NOT NULL）として持つ。テナントの主キーは持たない（[PostgreSQLの主キー](postgresql-primary-keys.md#主キーと公開用idの使い分け)）。
- `tenant_public_id`の既定値は`current_setting('app.current_tenant')::uuid`にし、RepositoryとseedのINSERTはこの列を書かない。列の値はトランザクションに設定したテナントになり、テナントを設定しないトランザクションのINSERTはエラーになる。uuidの`DEFAULT`の禁止の例外である（[PostgreSQLの主キー](postgresql-primary-keys.md#uuidの採番)）。
- 業務の表に`ENABLE ROW LEVEL SECURITY`と`FORCE ROW LEVEL SECURITY`を付ける。
- ポリシーは次の式で比べ、`current_setting`に`missing_ok`を渡さない。式は、読む行と、INSERTとUPDATEで書く行の両方に効かせる。
- ポリシーの対象は`PUBLIC`にする。表に触れるロールは、表の権限で絞る（[DB接続情報とロール分離](connections.md#モジュールのロール)）。

```sql
USING (tenant_public_id = current_setting('app.current_tenant')::uuid)
```

業務の一意索引と、同じモジュールの表どうしの外部キーは、[PostgreSQLの制約とインデックス](postgresql-constraints-and-indexes.md)の「外部キー」と「一意性」に従う。

業務の表には、`COPY FROM`で取り込めない。
PostgreSQLはRLSを受けるロールの`COPY FROM`を受け付けず、`FORCE ROW LEVEL SECURITY`で表の所有者もRLSを受けるためである。
業務の表への大量の取り込みが必要になったら、取り込みの方式をADRで決める。

## RLSの外に置く表

RLSの外に置く表は、`modulith`と`tenancy`のスキーマの表だけにする。

- **`modulith`**：Spring Modulithのイベントの出版とそのarchive、ShedLockの表である。テナントは、イベントの本文が運ぶ（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
- **`tenancy`**：テナント、利用者、ID連携、所属の表である。テナントが決まる前に引く。触れるのは`tenancy_module`だけである（[DB接続情報とロール分離](connections.md#モジュールのロール)）。

## テナントの設定

トランザクションマネージャーは、トランザクションを始めた直後に接続を準備する箇所で、モジュールのロールとテナントを1つのSQLで設定する。

```sql
SELECT set_config('role', ?, true), set_config('app.current_tenant', ?, true)
```

- どちらも`is_local`を`true`にし、トランザクションの間だけ効かせる。値はバインド変数で渡す。
- テナントは`TenantContext`の束縛から読む（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。束縛がないトランザクションでは`app.current_tenant`を設定しないため、そのトランザクションが業務の表に触れるとエラーになる。
- 接続プールの初期化のSQL（HikariCPの`connection-init-sql`）とセッション単位の`SET`には、テナントを書かない。

## 全テナントの行を書き換える処理

全テナントの行を書き換える処理（データの移行、開発用の代表データのreset）は、テナントを1つずつ設定して流す。
全テナントの行を読めるロールと、`BYPASSRLS`を持つロールは使わない。
マイグレーション用のロールも`FORCE ROW LEVEL SECURITY`でポリシーを受けるため、changesetのデータの移行もテナントを設定して流す。

開発用の代表データの「空」の判定とresetの削除も、`tenancy.m_tenant`のテナントごとに、そのテナントを設定して行う（[開発ワークフロー](../tooling/dev-workflow.md#代表データを入れ直すとき)）。

## RLSの検査

RLSの規則は次の検査で確かめる。
一意索引の先頭の列と外部キーの検査は、[PostgreSQLの制約とインデックス](postgresql-constraints-and-indexes.md)に示す。

- **カタログのテスト**：`SchemaConventionsTest`と`SchemaInspectionTest`（[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)）が、業務の表に`ENABLE`と`FORCE`があること、`tenant_public_id`の型、RLSの外の表が`modulith`と`tenancy`のスキーマの表だけであること、ポリシーの対象が`PUBLIC`であることを確かめる。
- **changesetのlint**：`ChangesetLintTest`が、RLSとポリシーのchangesetの書き方を確かめる。
- **`@DatabaseTest`**：別のテナントの行を読めず書けないことと、テナントを設定し忘れるとエラーになることを確かめる。
- **`@CommittedDatabaseTest`**：commitとrollbackの後、プールの接続にテナントが残らないことを確かめる。設定の後に空文字列が残る場合も、ポリシーが失敗する側に倒れることを確かめる。

## ノイジーネイバーへの対策

DBの負荷を平準化するため、アプリケーションでテナントごとに流量を制限する。
接続プールの大きさは、[ADR-019](../adr/ADR-019-define-resilience-and-capacity-guardrails.md)の接続予算に従う。

## テナントごとのスキーマ差

特定のテナントだけに独自のカラムやテーブルを追加しない。
試験的な機能を一部のテナントに提供する場合も、最終的にはテナント間でスキーマを同じに保つ。

## シャーディング

インスタンス分離以外の方式でテナント数が増え、DBのシャーディングが必要になった場合は、`tenant_public_id`のハッシュまたは範囲で機械的に振り分ける。
特定のテナントだけが大きく機械的に振り分けられない場合に限り、そのテナントを別のDBへ分ける。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
