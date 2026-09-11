# DBマイグレーションとjOOQコード生成

## 実行モデル

アプリケーションは起動時にLiquibaseを実行しません。
`spring.liquibase.enabled=false`を共通設定に置き、LiquibaseのSpring Boot依存も実行時クラスパスから除外しています。

スキーマ変更はアプリケーションのデプロイ前に、独立したジョブまたは作業者がGradleタスクを明示して適用します。
通常の`compileJava`、`test`、`bootJar`はLiquibaseとjOOQコード生成を起動せず、DBにも接続しません。

## コマンドの影響範囲（クイックリファレンス）

どのコマンドを、どういうときに叩けばよいか迷ったときの早見表です。
判断の軸は「そのコマンドがDBに何をするか（影響範囲）」です。

`-check`と`-preview`は必ず安全です。
`be-migrate`系は前へ進めるだけで、既存の状態を戻しません。
`be-verify-migrations`と`make test`は使い捨てDB専用で、開発DBや本番DBには触れません。
破壊的なのは`be-rollback`だけで、それも`CONFIRM_ROLLBACK=yes`がなければDBへ接続せず失敗します。

| コマンド                                                      | 何をするか                                                          | 影響範囲                         | 使うタイミング                          |
| ------------------------------------------------------------- | ------------------------------------------------------------------- | -------------------------------- | --------------------------------------- |
| `make be-schema-tag-check`                                    | 現在のスキーマタグがDBにあるか照会                                  | 読み取りのみ・安全               | 対象DBの状態を確認したいとき            |
| `make be-rollback-check DB_ROLLBACK_TAG=<tag>`                | 切り戻し対象タグの存在を確認                                        | 読み取りのみ・安全               | 切り戻し前の下調べ                      |
| `make be-rollback-preview DB_ROLLBACK_TAG=<tag>`              | 切り戻しSQLを生成（`build/reports/liquibase/rollback-preview.sql`） | DB変更なし・安全                 | 切り戻しの内容を実行前に確認            |
| `make be-generate-jooq`                                       | 現在のDBスキーマからjOOQコードを生成                                | DB変更なし（生成ソースを更新）   | スキーマは変えずにコードだけ再生成      |
| `make be-migrate`                                             | 現在のスキーマタグまで前進適用し、タグを確認                        | 追記のみ（前進）                 | 初回起動前・changeset追加後（ローカル） |
| `make be-refresh-jooq`                                        | 前進適用してからjOOQコードを生成                                    | 追記のみ（前進）                 | changeset追加後にまとめて実行           |
| `make be-verify-migrations`                                   | 使い捨てDBで適用・rollback・再適用・タグ確認                        | 隔離DB専用・安全                 | changeset追加後の検証・CI               |
| `make test`                                                   | 隔離スタックで上記検証を通してからテスト                            | 隔離DB専用・安全                 | 変更のローカル総合確認                  |
| `make be-release-migrate`                                     | 本番の前進適用（`MIGRATION_DB_*`必須）                              | 追記のみ（前進・本番）           | デプロイパイプラインから                |
| `make be-rollback DB_ROLLBACK_TAG=<tag> CONFIRM_ROLLBACK=yes` | 指定タグより後のchangesetを切り戻す                                 | **破壊的（データ損失の可能性）** | preview・バックアップ・影響確認の後だけ |

「安全」は、対象DBのスキーマとデータを変えないことを指します（`be-generate-jooq`はリポジトリの生成ソースを書き換えます）。
「前進」は、未適用のchangesetを新しく適用するだけで、適用済みの変更は戻さないことを指します。

各Makeターゲットの本体は、`backend`ディレクトリのGradleタスクを呼び出します。
`make help`で各ターゲットの一行説明を一覧表示できます。

## スキーマタグ

**スキーマタグ**は、DBスキーマのある状態に付けた名前です。
デプロイやマイグレーションの区切りとなる、復旧の目印として使います。

このタグは、稼働中のDBへ`tag`コマンドを手で打って付けるのではなく、changelogファイルの中に`tagDatabase` changesetとして書いておきます。
changelogはGitでコードと一緒に版管理されるので、タグもコードと同じ履歴で追えます。
稼働DBの状態に手を入れて付けるのではなく、ファイルに書いて管理する方式なので、これを宣言的と呼びます（対義語は、コマンドを打って付ける命令的な方式です）。

現在のスキーマタグは`backend/gradle.properties`の`databaseSchemaTag`に記録し、`migrateDatabase`が同じタグを`updateToTag`へ自動的に渡します。
実行時に`DB_TAG`を入力する必要はありません。

現在のタグは`schema-v1`です。

```yaml
databaseChangeLog:
  - changeSet:
      id: 002-tag-schema-v1
      author: spring-modulith-ai-dlc
      changes:
        - tagDatabase:
            tag: schema-v1
```

次のスキーマ版を追加するときは、スキーマ変更と独立したタグchangesetを連番順に追加します。
`tagDatabase`は他のChange Typeと同じchangesetへ入れません。

```text
003-add-example-column.yaml
004-tag-schema-v2.yaml
```

タグchangesetの追加と同じ変更で、`backend/gradle.properties`を次のように更新します。
`validateCurrentSchemaTag`は、このタグが連番changelogの末尾に一度だけ宣言されていることをDB接続前に検証します。

```properties
databaseSchemaTag=schema-v2
```

DBスキーマタグはDB変更の復旧点であり、アプリケーションのリリース番号ではありません。
DB変更のないアプリケーションリリースでは、新しいDBスキーマタグを作りません。
アプリケーションリリースはGitタグまたはコンテナイメージdigestで識別し、デプロイ履歴へ使用したDBスキーマタグを記録します。

命令的なGradleの`tag`タスクは誤操作を防ぐため失敗します。
過去の`migrateAndTagDatabase`、`tagDatabase`集約タスク、`DB_TAG`入力は廃止しました。

## ローカル開発

PostgreSQLを起動し、ルートの`.env`に接続情報を設定してから現在のスキーマタグまで適用します。

```bash
make compose-up
make be-migrate
```

`be-migrate`は`updateToTag`と`assertSchemaTagExists`を順序実行します。
changelogに現在タグより後のchangesetが存在しても、自動的には適用しません。

changesetを追加した後は、使い捨てDBでrollback可能性を検証します。

```bash
make test
```

`make test`は隔離したPostgreSQLを起動し、全changesetの適用、rollback、再適用、現在タグの存在確認を実行してからバックエンドテストを開始します。
終了後はテスト用ボリュームを削除します。

マイグレーションを適用して最新スキーマのjOOQソースを生成する場合は、次のコマンドを使います。

```bash
make be-refresh-jooq
```

現在のDBを変更せず、コード生成だけを再実行する場合は次のコマンドを使います。

```bash
make be-generate-jooq
```

生成先は`backend/src/generated/jooq`です。
生成コードはchangesetと同じ変更に含め、リリースビルドが稼働DBへ接続しなくても再現できる状態を保ちます。

## jOOQの日時型

jOOQコード生成には公式`org.jooq.jooq-codegen-gradle`プラグイン3.21.7を使用します。
PostgreSQLの`TIMESTAMP WITH TIME ZONE`と`TIMESTAMPTZ`は、forced typeによって`java.time.Instant`へマッピングします。
`OffsetDateTime`をアプリケーションの絶対時刻モデルへ持ち込みません。

`jooqCodegen`は現在のDBから生成するため、changeset追加後は単独実行せず`migrateAndGenerateJooq`を使用します。
通常のJavaコンパイルからコード生成を分離しているため、`compileJava`と`bootJar`はDBへ接続しません。

## Gradleタスク

`backend`ディレクトリでは次のタスクを直接実行できます。

```bash
./gradlew migrateDatabase
./gradlew checkCurrentSchemaTag
./gradlew verifyDatabaseMigrations
./gradlew jooqCodegen
./gradlew migrateAndGenerateJooq
```

- `migrateDatabase`：`databaseSchemaTag`まで`updateToTag`を実行し、そのタグの存在を確認します。
- `checkCurrentSchemaTag`：現在のスキーマタグが対象DBに存在することを確認します。
- `verifyDatabaseMigrations`：使い捨てDBで全changesetの適用、rollback、再適用、現在タグの存在確認を実行します。
- `jooqCodegen`：現在のDBスキーマを読み取り、コード生成だけを実行します。
- `migrateAndGenerateJooq`：現在のスキーマタグまで適用してから`jooqCodegen`を実行します。

`verifyDatabaseMigrations`は適用済みchangesetを実際に戻すため、使い捨てDB専用です。
開発DBと本番DBでは実行しません。

Liquibaseプラグインの`update`はchangelog内の未適用changesetをすべて適用します。
現在タグより後の開発中changesetも対象になるため、本番のマイグレーション入口には使用しません。

## 接続情報

接続情報は`build.gradle`へ保存せず、環境変数またはCIのシークレットから注入します。
ユーザー名とパスワードが未設定のままDB操作タスクを実行すると、タスクは接続前に失敗します。

接続変数の区分は、同じ環境内に別々の物理DBを用意するためのものではありません。
開発、CI、ステージング、本番のDBは環境ごとに分離しますが、ステージングまたは本番のアプリケーションとLiquibaseは、通常は同じデータベースとスキーマへ異なる権限で接続します。

- **アプリケーション接続**：`DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`、`DB_SCHEMA`を使います。
  Spring Bootはこれらの値からJDBC URLを組み立てます。
- **Liquibase接続**：`MIGRATION_DB_URL`、`MIGRATION_DB_USERNAME`、`MIGRATION_DB_PASSWORD`、`MIGRATION_DB_SCHEMA`を使います。
  ステージングと本番では、DDL権限を持つマイグレーション専用アカウントを指定します。
- **jOOQ生成接続**：`CODEGEN_DB_URL`、`CODEGEN_DB_USERNAME`、`CODEGEN_DB_PASSWORD`、`CODEGEN_DB_SCHEMA`を使います。
  CIでコード生成する場合は、changesetから再現した使い捨てDBを指定します。
- **Gradle用URLの上書き**：`DB_URL`はGradleの共通接続先を上書きします。
  Spring Bootのデータソースは`DB_URL`を参照せず、`DB_HOST`、`DB_PORT`、`DB_NAME`からURLを組み立てます。

Gradleタスクのフォールバック順は次のとおりです。

```text
MIGRATION_DB_* -> DB_*
CODEGEN_DB_*   -> MIGRATION_DB_* -> DB_*
```

このフォールバックは、ローカルと使い捨てのCI環境で一つのDBと資格情報を共用するために使います。
ステージングと本番ではフォールバックに依存せず、アプリケーション用の`DB_*`とマイグレーション用の`MIGRATION_DB_*`を個別に注入します。
`be-release-migrate`とDB切り戻し用Makeターゲットはローカルの`.env`を読み込まず、四つの`MIGRATION_DB_*`がすべて注入されていなければGradle実行前に失敗します。
アプリケーション用アカウントには業務処理に必要なDML権限を与え、`CREATE`、`ALTER`、`DROP`を与えません。
Liquibase用アカウントには、DDL、Liquibase管理テーブルの更新、データ移行changesetに必要なDMLの権限を与えます。

| 環境         | アプリケーション             | Liquibase                   | jOOQ生成                                            |
| ------------ | ---------------------------- | --------------------------- | --------------------------------------------------- |
| ローカル     | `DB_*`                       | `DB_*`へフォールバック      | `DB_*`へフォールバック                              |
| CIテスト     | `DB_*`                       | 同じ使い捨てDB              | 必要な場合だけ同じ使い捨てDB                        |
| ステージング | `DB_*`                       | 専用の`MIGRATION_DB_*`      | 実行しない                                          |
| 本番         | `DB_*`                       | 専用の`MIGRATION_DB_*`      | 実行しない                                          |
| コード生成CI | アプリケーションを起動しない | 使い捨てDBへchangesetを適用 | `CODEGEN_DB_*`またはLiquibase接続へのフォールバック |

本番のDBマイグレーションジョブとアプリケーションデプロイでは、`jooqCodegen`と`migrateAndGenerateJooq`を実行しません。
生成コードはchangesetと同じ変更として`backend/src/generated/jooq`へコミットし、リリースビルドはそのコミット済みソースをコンパイルします。
本番でコード生成すると、リリース成果物が稼働DBの状態へ依存し、コード生成用のメタデータ参照権限も本番へ持ち込むことになります。
changeset追加時のコード生成とレビューを開発またはCIで完了させ、本番では`make be-release-migrate`によるマイグレーションと、コミット済み生成コードを含むアプリケーションのデプロイだけを実行します。
稼働DBの資格情報を開発端末へ配布してコード生成する運用も行いません。

## CIでの検証

バックエンドCIは使い捨てPostgreSQLに対して`verifyDatabaseMigrations`を実行します。
Liquibaseの`updateTestingRollback`によって、全changesetを適用し、同じchangesetを戻し、再び適用します。
その後に`assertSchemaTagExists`で現在のスキーマタグを確認し、通常のマイグレーションとバックエンドテストを実行します。

この検証により、rollback定義の不足とタグchangesetの追加漏れをPull Requestで検出します。
本番DBのデータ復元可能性までは保証しません。

## デプロイ順序

後方互換性を保つchangesetでは、デプロイを次の順序に分けます。

1. CIが使い捨てDBでマイグレーションとrollback再適用を検証します。
2. リリース対象のchangeset、バックアップ、復旧手順を承認します。
3. 単一のマイグレーションジョブが`make be-release-migrate`を実行します。
4. `updateToTag`と`assertSchemaTagExists`が成功した場合だけアプリケーションをデプロイします。
5. デプロイ履歴へアプリケーション識別子とDBスキーマタグを記録します。

```bash
make be-release-migrate
```

本番でタグ名を手入力しません。
デプロイジョブはリポジトリに固定された`databaseSchemaTag`を使用します。
複数のアプリケーションインスタンスから同時にマイグレーションを起動しません。

破壊的変更はexpand-and-contractで分割し、旧バージョンと新バージョンが併存する時間帯に必要な列やテーブルを先に削除しない構成にします。

## DB切り戻し

DB切り戻しは指定タグより後に適用されたchangesetを新しい順に戻します。
テーブルや列を再作成できるrollbackでも、削除済みデータの復元は保証されません。

本番の切り戻し対象タグは作業者が記憶や推測で選ばず、直前に成功したデプロイ履歴からパイプラインが取得します。
現時点では本番デプロイワークフローがないため、Makeターゲットへ`DB_ROLLBACK_TAG`として明示します。

切り戻し前にアプリケーションの書き込みを停止し、対象DBのバックアップと復元手順を確認します。
その後、タグの存在とLiquibaseが生成するSQLを確認します。

```bash
make be-rollback-check DB_ROLLBACK_TAG=schema-v1
make be-rollback-preview DB_ROLLBACK_TAG=schema-v1
```

プレビューSQLは`backend/build/reports/liquibase/rollback-preview.sql`へ出力されます。
SQLの対象オブジェクト、データ損失、ロック時間、切り戻し後に起動するアプリケーションとの互換性を確認します。

確認後に限り、明示確認を付けて切り戻します。

```bash
make be-rollback \
  DB_ROLLBACK_TAG=schema-v1 \
  CONFIRM_ROLLBACK=yes
```

`DB_ROLLBACK_TAG`と`CONFIRM_ROLLBACK=yes`がなければMakeターゲットはDBへ接続せず失敗します。
Gradleを直接使う場合も、`-PliquibaseTag=<tag>`と`-PconfirmRollback=true`が必要です。

```bash
./gradlew checkRollbackTag \
  -PliquibaseTag=schema-v1
./gradlew previewDatabaseRollback \
  -PliquibaseTag=schema-v1 \
  -PliquibaseOutputFile=build/reports/liquibase/rollback-preview.sql
./gradlew rollbackDatabase \
  -PliquibaseTag=schema-v1 \
  -PconfirmRollback=true
```

切り戻し後は`DATABASECHANGELOG`とDBスキーマを確認し、そのスキーマと互換性のあるアプリケーションをデプロイします。
rollback定義のないchangesetやデータ復元が必要な障害では、このコマンドだけに依存せず、承認済みの復旧手順とバックアップを使用します。

## 参照資料

- Spring Boot Database Initialization: <https://docs.spring.io/spring-boot/how-to/data-initialization.html>
- Spring Boot Common Application Properties: <https://docs.spring.io/spring-boot/appendix/application-properties/index.html>
- Liquibase Gradle Plugin Usage: <https://github.com/liquibase/liquibase-gradle-plugin/blob/main/doc/usage.md>
- Liquibase `tagDatabase`: <https://docs.liquibase.com/reference-guide/change-types/tagDatabase>
- Liquibase `update-to-tag`: <https://docs.liquibase.com/commands/update/update-to-tag.html>
- Liquibase `update-testing-rollback`: <https://docs.liquibase.com/commands/update/update-testing-rollback.html>
- Liquibase Rollback: <https://docs.liquibase.com/community/reference-guide-5-0-4/init-update-and-rollback-commands/rollback>
- jOOQ公式Gradleプラグイン: <https://github.com/jOOQ/jOOQ/tree/main/jOOQ-codegen-gradle>
