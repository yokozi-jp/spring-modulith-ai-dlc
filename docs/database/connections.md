---
type: Convention
title: 'DB接続情報とロール分離'
description: アプリケーション、Liquibase、jOOQ生成が使う接続先と資格情報の環境変数、スキーマの所有、環境ごとのロール分離、モジュールのロールへの切り替え、アプリの接続ごとの時間の上限を定める。接続用の環境変数やDBロールを追加、変更するとき、ステージングや本番のDBを準備するときに読む。
tags: [convention, database, liquibase, security, credentials]
---

# DB接続情報とロール分離

接続先（`DB_HOST`、`DB_PORT`、`DB_NAME`）はアプリケーションとマイグレーションで共有し、資格情報だけを役割ごとに分けます。
アプリケーションは`DB_USERNAME`（DMLのみ。業務のスキーマはモジュールのロールへ切り替えて使う）、LiquibaseとjOOQ生成は`MIGRATION_DB_USERNAME`（DDL）で接続し、資格情報にフォールバックはありません。
接続情報は`build.gradle`へ保存せず、環境変数またはCIのシークレットから注入します。

## 接続情報

接続情報は`build.gradle`へ保存せず、環境変数またはCIのシークレットから注入します。
ユーザー名とパスワードが未設定のままDB操作タスクを実行すると、タスクは接続前に失敗します。

接続変数の区分は、同じ環境内に別々の物理DBを用意するためのものではありません。
開発、CI、ステージング、本番のDBは環境ごとに分離しますが、同じ環境のアプリケーションとLiquibaseは同じデータベースへ異なる権限で接続します。

接続先（ホスト、ポート、DB名）はアプリケーションとマイグレーションで共有し、役割ごとに変わるのは資格情報だけです。
スキーマ名は環境差を持たないため、ビルド設定とchangesetで固定します。

- **共通の接続先**：`DB_HOST`、`DB_PORT`、`DB_NAME`を使います。
  アプリケーション、Liquibase、jOOQ生成のいずれもこのDBへ接続します。
- **Liquibase管理スキーマ**：`liquibase`に固定します。
  Liquibaseがchangesetより先に管理テーブルを作るため、DB初期化はこのスキーマだけを事前作成します。
- **モジュール所有スキーマ**：Liquibase changesetで作成します。
  現在はSpring Modulith共有テーブル用の`modulith`だけです。
  jOOQの生成対象は`backend/gradle/database.gradle`の一覧で管理します。
- **アプリケーション接続の資格情報**：`DB_USERNAME`、`DB_PASSWORD`を使います。
  Spring Bootは共通の接続先とこの資格情報からJDBC URLを組み立てます。
- **マイグレーション接続の資格情報**：`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`を使います。
  Liquibaseとその接続を共用するjOOQ生成が使います。
  接続先は共通の`DB_*`から組み立て、資格情報だけを差し替えます。
  ステージングと本番では、DDL権限を持つマイグレーション専用アカウントを指定します。
  jOOQコード生成はローカルとCIでのみ実行し、本番では実行しないため、生成専用の資格情報は設けません。
- **Gradle用URLの上書き**：`DB_URL`はGradleの共通接続先を上書きします。
  Spring Bootのデータソースは`DB_URL`を参照せず、`DB_HOST`、`DB_PORT`、`DB_NAME`からURLを組み立てます。

## 接続ごとの時間の上限

アプリケーションの接続には、HikariCPの`connection-init-sql`が新しい物理接続ごとに次の3つの上限を設定します。
値はミリ秒の整数で、`application.yaml`に既定値はなく、未設定ならアプリケーションの起動に失敗します。

- **`DB_LOCK_TIMEOUT_MS`**：`lock_timeout`です。
  表や行のロックを待つ時間の上限で、超えると文はSQLSTATE `55P03`で失敗します。
- **`DB_STATEMENT_TIMEOUT_MS`**：`statement_timeout`です。
  1つの文の実行時間の上限で、超えると文はSQLSTATE `57014`で取り消されます。
- **`DB_IDLE_IN_TRANSACTION_TIMEOUT_MS`**：`idle_in_transaction_session_timeout`です。
  トランザクションを開いたまま次の文を待つ時間の上限で、超えるとセッションが終了します。

値は起動時に検証しないため、設定するときに次の条件を守ります（[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)）。

- 3つとも1以上にします。
  PostgreSQLは`0`を上限なしとして受け付けるため、`0`を渡すと無期限に待ちます。
- `lock_timeout`は`statement_timeout`より短くします。
- `statement_timeout`は`DB_POOL_CONNECTION_TIMEOUT_MS`以下にします。
- `idle_in_transaction_session_timeout`は、トランザクションの中で呼ぶ外部システムの最悪の時間より長くします。
  決済のListenerはトランザクションの中で決済代行を呼び、待つ間はトランザクションを開いたままです（[ADR-050](../adr/ADR-050-define-backend-class-roles-and-naming.md)）。
  今の最悪の時間は3秒で、計算と検査は[外部システムのClient](../backend/class-roles/external-client.md)と`DemoApplicationTest`にあります。

`postgresql.conf`、RDSのパラメータグループ、`ALTER ROLE`では設定しません。
1つのトランザクションだけ上限を変えるときは、`SET LOCAL`を使い、理由をコードに書きます。
LiquibaseとjOOQ生成はHikariCPを通らないため、この上限を受けません。
値の決め方と開始値は[ADR-055](../adr/ADR-055-set-db-time-limits-per-connection.md)にあります。

## ロール分離

資格情報にフォールバックはありません。
マイグレーションとjOOQ生成を実行するときは、`MIGRATION_DB_USERNAME`と`MIGRATION_DB_PASSWORD`を全環境で明示します。
これにより、ローカルからテスト、ステージング、本番まで、接続の解決経路と権限モデルが一致し、環境ごとに異なる分岐を通りません。
ローカルとテストも二ロールで動かします。
`docker/compose.yml`と`docker/compose-test.yml`では、PostgreSQLのイメージが作るsuperuser（`postgres`）をコンテナの初期化だけに使います。
`docker/initdb`は初回起動時に、このsuperuserでマイグレーション用ロール、`liquibase`スキーマ、DML限定のアプリケーション用ロール、モジュールのロール（`03-create-module-roles.sh`）を作成します。
マイグレーション用ロールはsuperuserでも`BYPASSRLS`でもなく、DBへは`CREATE`だけを追加で与え、`liquibase`スキーマと、changesetが作るスキーマと表を所有します。
アプリケーション、Liquibase、jOOQ生成、テストのDB操作はsuperuserで接続しません。
superuserのパスワードは、ローカルでは`.env`の`POSTGRES_SUPERUSER_PASSWORD`をComposeのsecretで渡し、テストでは使い捨ての値を使います。
マイグレーション用ロールがsuperuserでも`BYPASSRLS`でもないことは、カタログのテスト（`SchemaInspectionTest`の規則G2）が確かめます。
001 changesetは`modulith`スキーマを作成し、そのUSAGEとイベント出版テーブルだけのDML権限を`DB_USERNAME`へ付与します。
業務のスキーマ（`product`、`ordering`、`payment`）の権限は、012 changesetがアプリケーション用ロールから取り上げ、モジュールのロールへ移します（次の「モジュールのロール」）。
`tenancy`スキーマの権限は、014 changesetが作成のときからモジュールのロールだけに与えます。
Liquibase管理スキーマと管理テーブルへのアプリ権限は付与しません。
001 changesetを初期状態から書き換えたことと、既存のボリュームでは`docker/initdb`が再実行されずマイグレーション用ロールがsuperuserのまま残ることから、既存DBは移行せず`task compose-reset CONFIRM_RESET=yes`で作り直します。
ステージングと本番も既存DBを再利用せず、`liquibase`スキーマだけをマイグレーション用ロールの所有で作ってから初回マイグレーションを実行します。
001 changesetがアプリケーションロール（`DB_USERNAME`）へGRANTし、012 changesetがモジュールのロールへGRANTするため、ステージングと本番では、アプリケーションロールとモジュールのロールを初回マイグレーションより前にRDS側で作成しておきます。
`docker/initdb`のような自動作成経路がないため、未作成のままマイグレーションを実行するとGRANTで失敗します。
`be-release-migrate`とDB切り戻し用Taskfileのタスクはローカルの`.env`を読み込まず、共通の接続先（`DB_URL`、または`DB_HOST`/`DB_PORT`/`DB_NAME`）と権限付与先の`DB_USERNAME`、`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`が注入されていなければGradle実行前に失敗します。
アプリケーション用アカウントには業務処理に必要なDML権限を与え、`CREATE`、`ALTER`、`DROP`を与えません。
Liquibase用アカウントには、DDL、Liquibase管理テーブルの更新、データ移行changesetに必要なDMLの権限を与えます。

| 環境         | 接続先       | アプリケーションロール（DML） | マイグレーションロール（DDL、所有）  | モジュールのロール         | jOOQ生成           |
| ------------ | ------------ | ----------------------------- | ------------------------------------ | -------------------------- | ------------------ |
| ローカル     | 共通の`DB_*` | `demo`（initdbが作成）        | `demo_migration`（initdbが作成）     | initdbが作成               | 同じ接続で生成     |
| CIテスト     | 共通の`DB_*` | `demo`（initdbが作成）        | `demo_migration`（initdbが作成）     | initdbが作成               | 必要な場合だけ生成 |
| ステージング | 共通の`DB_*` | 専用アカウント                | 専用アカウント                       | 構築の手順で先に作成       | 実行しない         |
| 本番         | 共通の`DB_*` | 専用アカウント                | 専用アカウント                       | 構築の手順で先に作成       | 実行しない         |

本番でjOOQコードを生成しない理由は[jOOQコード生成と日時型](jooq-codegen.md)にあります。

## モジュールのロール

業務のスキーマは、アプリケーション用ロールではなく、モジュールごとのログインできないロール`<module>_module`（`product_module`、`ordering_module`、`payment_module`、`tenancy_module`）が読み書きする（[ADR-090](../adr/ADR-090-switch-to-module-database-roles.md)）。
別のモジュールの表への書き込みと、依存の方向に逆らう読み取りは、DBの権限エラー（SQLSTATE `42501`）で止まる。

- **切り替え**：`ModuleRoleTransactionManager`が、トランザクションを始めた直後に、トランザクションの名前（クラス名とメソッド名）から引いたモジュールのロールへ`set_config('role', ?, true)`で切り替える。トランザクションの終わりで元に戻るため、プールの接続にロールが残らない。モジュールが分からない名前（Spring Modulithの内部、ShedLock、名前のない`TransactionTemplate`）はアプリケーション用ロールのまま動く。
- **アプリケーション用ロール**：業務のスキーマに権限を持たない。`modulith`スキーマ（イベントの出版とShedLockの表）だけを読み書きする。モジュールのロールの継承はなく、`SET ROLE`で切り替えるだけのメンバーである。
- **モジュールのロールの権限**：自分のスキーマのUSAGEと表のSELECT、INSERT、UPDATE、DELETE。依存の方向（`ordering`から`product`、`payment`から`ordering`）にだけ、依存先のスキーマのUSAGEとSELECT。`modulith`スキーマのUSAGEと`modulith.event_publication`へのINSERT。`tenancy`はどのモジュールにも依存せず、どのモジュールも`tenancy`に依存しないため、依存の方向のSELECTを持たない。
- **新しい表**：権限はスキーマごとの`ALTER DEFAULT PRIVILEGES`で決まるため、既存のスキーマに表を足すchangesetは権限を書かない。新しいモジュールのスキーマを作るchangesetは、そのスキーマのUSAGEと`ALTER DEFAULT PRIVILEGES`をモジュールのロールへ与える。権限のSQLの書き方はchangesetの検査（規則G3）、権限の漏れと余りはカタログのテスト（`SchemaInspectionTest`の規則G4、G5）が止める。
- **ロールの作成**：ロールはマイグレーションより先に、環境を作る手順で作る。マイグレーション用ロールに`CREATEROLE`を持たせない。ロールの名前は固定で、1つのDBクラスターに1つの環境を置く。
- **限界**：アプリケーション用ロールは、どのSQLからでも`SET ROLE`で別のロールへ切り替えられる。DBのロールで止められるのは誤りで、故意の迂回は止められない。ArchUnitの規則（接続の設定を変える`DSLContext`のAPIの禁止、`JdbcTemplate`と生のJDBCの禁止）で補う（[ArchUnitのテスト](../backend/architecture-tests.md)）。

ステージングと本番では、マイグレーションの前に、スーパーユーザー相当のアカウント（RDSでは`rds_superuser`）で、モジュールごとに次を実行する。
`<app>`は`DB_USERNAME`である。
モジュールを足すときは、`docker/initdb/03-create-module-roles.sh`の`modules`と`ModuleRoles.MODULES`にも同じ名前を足す。

```sql
CREATE ROLE product_module NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
GRANT product_module TO <app> WITH INHERIT FALSE, SET TRUE;
```

`task compose-reset CONFIRM_RESET=yes`で作り直すまで、既存のローカルのDBにはモジュールのロールがない。
`docker/initdb`は初回起動時にだけ実行されるためである。

## 参照資料

- Spring Boot Common Application Properties: <https://docs.spring.io/spring-boot/appendix/application-properties/index.html>
- PostgreSQL Client Connection Defaults: <https://www.postgresql.org/docs/18/runtime-config-client.html>
