# 共通の領域の docs の差分の調査（#327）

この文書は #327 の調査の記録で、merge しない。

## 問い

同じ出典（フューチャー株式会社のアーキテクチャ設計ガイドライン、CC BY 4.0）から取り込んだ共通の領域で、向こう（`yokozi-jp/agent-software-factory`、commit `29ba897`）が原文から直した点のうち、こちら（`yokozi-jp/spring-modulith-ai-dlc`、main `ad4bdab`）に足すものはあるか。

## 調べ方

- 向こうの各領域の `docs/<領域>/about.md` の「原文からの変更点」を 1 項目ずつ読み、変えた文書の箇所を確かめた。
  定型の項目（文書の分割、OKF の frontmatter、VitePress の記法、リンクの付け替え、図の mermaid 化、Markdown の検査のための修正）は除いた。
- こちらには項目ごとの変更の一覧がない（ADR-040 は書き直しの方針だけを持つ）ので、こちらの docs の本文と、関わるコードと設定を直接読んで照らした。
- 判定は、同じ決定、こちらにない、こちらに当てはまらない、意図して違う決定、の 4 つにした。
- 領域ごとに読む担当を分け、結果をこの文書にまとめた。

比べた領域と、こちらの比較対象は次のとおり。

| 向こう                                                                                                                                                         | こちら                                                                                                                                |
| :------------------------------------------------------------------------------------------------------------------------------------------------------------- | :------------------------------------------------------------------------------------------------------------------------------------ |
| `docs/web-api/`                                                                                                                                                | `docs/web-api/`、ADR-013、ADR-014、ADR-016、ADR-062、ADR-066                                                                          |
| `docs/db/`（PostgreSQL）                                                                                                                                       | `docs/database/`、ADR-003、ADR-004、ADR-005、ADR-054、ADR-055、ADR-060                                                                |
| `docs/interface/`、`docs/async/`                                                                                                                               | `docs/integration/`                                                                                                                   |
| `docs/logging/`                                                                                                                                                | `docs/observability/`                                                                                                                 |
| `docs/web-frontend/`                                                                                                                                           | `docs/frontend/`                                                                                                                      |
| `docs/architecture-principles/`、`docs/code-review/`、`docs/non-functional-requirements/`、`docs/performance-test/`、`docs/report/`、`docs/technical-writing/` | `docs/principles/`、`docs/code-review/`、`docs/pull-request/`、`docs/nfr/`、`docs/performance-test/`、`docs/report/`、`docs/writing/` |

メールとバッチとデータガバナンスと Markdown の設計書は、#323、#314、#326、#324 で比べたので除いた。

## 取り込んだ原文の版

向こうの docs とコミットは、取り込んだ原文の commit を記録していない。
向こうが取り込んだのは 2026-10-23 から 2026-10-24（`bcd3c73`、`f4691ae`、`ce87da0`）で、原文の `e309a6d`（2026-09-30）の次の commit は `c48ffd1`（2026-11-02）なので、向こうの元も `e309a6d` と推定した（日付からの推定）。
こちらは ADR-040 で `e309a6d` に固定している。
したがって、向こうの「原文からの変更点」は、こちらと同じ原文に対する差分として読める。

`e309a6d` より後の原文の commit は 6 件で（`gh api` の commits で確認）、Web API、DB、I/F、ログ、非同期、Web フロントエンド、ここで比べたほかの領域の本文には触れていない。
変わったのは非機能要件とソフトスキルの index の記事へのリンク、ソフトスキルの合意形成編の新設、VitePress のテーマである。

## 結果

### Web API

| 判定           | 項目                                                             | 向こう                                    | こちら                                                                    | 扱い                            |
| :------------- | :--------------------------------------------------------------- | :---------------------------------------- | :------------------------------------------------------------------------ | :------------------------------ |
| 同じ           | `Deprecation` を RFC 9745 の Date にする                         | `about.md:31`                             | `docs/web-api/versioning.md:62`                                           | なし                            |
| ない           | 暦日を `full-date` で返す                                        | `about.md:32`、`response-body.md:107,117` | `docs/web-api/response-body.md:122-128` は瞬間の規則だけ                  | #586                            |
| 違う           | 瞬間をミリ秒 3 桁に固定する                                      | `response-body.md:116`                    | マイクロ秒（`docs/datetime/timezone-conventions.md:33-34`）               | 取り込まない                    |
| 同じ           | JSON Merge Patch を RFC 7396 にする                              | `about.md:33`                             | `docs/web-api/http-methods.md:54`                                         | なし                            |
| ない           | Idempotency-Key の Draft を 08 にする                            | `about.md:34`                             | ADR-013:87,161 が「失効した Internet-Draft」と書く                        | #540                            |
| ない           | 冪等キーの一意キーにテナントを含める                             | `about.md:35`                             | ADR-013:83                                                                | #540、#363                      |
| ない           | Idempotency-Key の必須と保存の決まり                             | `about.md:36`                             | ADR-013:80-86                                                             | #540（採用済み）                |
| 同じ           | ステータスコードとメソッドの出典を RFC 9110 と RFC 9112 にする   | `about.md:37-38`                          | `docs/web-api/status-codes.md:10`、`http-methods.md:43`                   | なし                            |
| 違う           | HSTS の値の書き損じを直す                                        | `about.md:39`                             | ADR-014:71,98（配信点で付け、`includeSubDomains` は保留）                 | なし                            |
| ない           | BFF のセッションの Cookie を `SameSite=Strict` にする            | `about.md:40`                             | `backend/src/main/resources/application.yaml:298`（`lax`）                | #345                            |
| ない           | セッションの Cookie の名前に `__Host-Http-` を付ける             | `about.md:41`                             | `application.yaml:292`（`APP_SESSION`）                                   | #345                            |
| 同じ           | CSRF 対策を必須にし、`SameSite` だけに頼らない                   | `about.md:41`                             | ADR-066:74-77                                                             | なし                            |
| 当てはまらない | OAC の署名と Bearer が両立しない                                 | `about.md:42`                             | こちらは Lambda と OAC を使わない                                         | なし                            |
| 当てはまらない | Bearer の性能の評価と BFF の推奨（RFC 10017）                    | `about.md:43`                             | 比較表を写さず、ADR-007 でセッション方式に決めた                          | なし                            |
| 同じ           | CORS を入れず同一オリジンにする                                  | `about.md:44`                             | ADR-014:36,39、ADR-033                                                    | なし                            |
| 違う           | エラーの `title` を英語に固定する                                | `about.md:45`                             | ADR-016:49（日本語と英語）                                                | 取り込まない（#309 で決定済み） |
| 同じ           | route にない method に `Allow` 付きの 405                        | `about.md:46`                             | `docs/web-api/status-codes.md:41,61`、`ApiErrorContractTest.java:131-135` | #334                            |
| 同じ           | 403 は見えるが許されない操作                                     | `about.md:47`                             | `docs/web-api/status-codes.md:64-66`                                      | なし                            |
| 違う           | 状態の不一致を 409 にする                                        | `about.md:47`                             | ADR-062:50-51（422）                                                      | 取り込まない                    |
| 当てはまらない | Better Auth の `/api/auth/*` だけを Problem Details の例外にする | `about.md:48`                             | Better Auth を使わない                                                    | なし                            |

向こうの差分ではないが、照らす途中で見つけたこちらの誤りがある。

- `docs/web-api/http-methods.md:30` の「HTTP実装の推奨上限（8000オクテット）」は、RFC 9112 の 3 と意味が逆である。
  RFC 9112 の 3 は、少なくとも 8000 オクテットのリクエストラインを受け付けることを RECOMMENDED としている。
- `docs/web-api/status-codes.md:54` は 414 を使わないとするが、RFC 9112 の 3 は、解釈する気のない長さの request-target に 414 を返すことを MUST としている。
  向こうも同じ扱いで、実際のサーバーの応答は確かめていない。
- どちらも HTTP の細部なので、#334 に渡す。

Idempotency-Key の Draft は、datatracker で draft-08（2026-10-22 公開、2027-04-25 まで有効）を確かめた。
2.1 は値を Structured Field の String（`"<UUID>"`）とし、2.7 は本文が違う再利用に 422 を SHOULD とする。
本文が違う再利用を、#540 は 400 にしている（向こうも 400）。

`SameSite=Strict` には、IdP からのログインのコールバックがサイトをまたぐ遷移で、`Strict` の Cookie が付かないという懸念がある（推論、確かめていない）。
Spring Security は既定で認可の要求をセッションに保存するので、コールバックでセッションが見つからずログインが失敗しうる。
この懸念は #345 に渡す。

### DB（PostgreSQL）

| 判定           | 項目                                                        | 向こう                                      | こちら                                                                                | 扱い                  |
| :------------- | :---------------------------------------------------------- | :------------------------------------------ | :------------------------------------------------------------------------------------ | :-------------------- |
| 当てはまらない | DynamoDB と LocalStack                                      | `about.md:40`                               | DynamoDB を使わない                                                                   | なし                  |
| 同じ           | ヒント句の崩れを直す                                        | `about.md:38`                               | 例を写していない（`docs/database/postgresql-performance.md:14-21`）                   | なし                  |
| 同じ           | LTS と EOL の表をやめる                                     | `about.md:39`                               | 表がない（`postgresql-version-upgrade.md:19-20`）。一次情報へのリンクもない           | #589 でリンクを足す   |
| 同じ           | SQL に 1 対 1 で対応しない O/R マッパを使わない             | `about.md:41`                               | ADR-003 で jOOQ                                                                       | なし                  |
| 同じ           | クエリビルダーの書き方                                      | `about.md:42`                               | `docs/database/jooq-usage.md:19-43`、`connections.md:42-67`                           | なし                  |
| 決定済み       | 生成した型と migration のずれを CI で止める                 | `postgresql-migration.md:92`                | ADR-004:44                                                                            | #294 で見送り（#253） |
| 当てはまらない | dbmate と SQL の正本                                        | `about.md:43`                               | Liquibase（ADR-005）                                                                  | なし                  |
| ない           | migration のロック待ちの上限（`lock_timeout`）              | `about.md:44`、`postgresql-migration.md:88` | `docs/database/connections.md:66`、ADR-055:93                                         | #589                  |
| 違う           | down を失敗させ、前に進めるだけにする                       | `postgresql-migration.md:90`                | rollback を定義して CI で確かめる（ADR-005、`migrations.md:59-66`）                   | 取り込まない          |
| 違う           | seed を冪等にしない                                         | `about.md:45`                               | 業務データが空のときだけ入れる（`LocalDataSeeder.java`）                              | #308 で決定済み       |
| 違う           | 結合テストのテンプレートの DB に seed を入れる              | `about.md:46`                               | fork ごとの DB とテストごとのロールバック（`docs/backend/testing-database.md:10-26`） | 取り込まない          |
| 決定済み       | 前の版のアプリを PR の migration の後のスキーマで流す       | `about.md:47`、ADR 0037                     | `runbook-deploy-and-rollback.md:32`                                                   | #278 で見送り（#253） |
| 同じ           | タイムゾーンを IANA の名前で持つ                            | `about.md:48`                               | `postgresql-temporal-data.md:75-80`                                                   | なし                  |
| ない           | 現在時刻を DB の関数に持たせない                            | `about.md:49`                               | `postgresql-database-objects.md:17` が Oracle の互換関数を許す                        | #586                  |
| 違う           | `uuidv7()` を版で条件づける                                 | `about.md:50`                               | DB で採番しない（ADR-060）                                                            | なし                  |
| 同じ           | 404 の参考リンクを消す                                      | `about.md:51`                               | 写していない                                                                          | なし                  |
| 同じ           | `double` を `double precision` に直す                       | `about.md:52`                               | `postgresql-data-types.md:42`                                                         | なし                  |
| 同じ           | モジュールの NOLOGIN のロール                               | `about.md:53`                               | ADR-094、`connections.md:110,120`                                                     | #307 で採用済み       |
| 決定済み       | 競合と原子性のテストの書き方（新しい文書）                  | `about.md:54`、`concurrency-testing.md`     | `TableWriterConcurrencyTest.java`                                                     | #297 で見送り（#253） |
| 同じ           | 楽観排他を更新件数で判定する                                | `about.md:55`                               | ADR-054                                                                               | #338                  |
| 同じ           | 共通カラムをアプリがバインドし、外部キーと CHECK を置かない | `about.md:56`                               | `postgresql-common-columns.md:59`                                                     | #338、#363、#584      |
| 同じ           | カタログで規約を確かめる（新しい文書）                      | `about.md:57`                               | `docs/backend/architecture-tests.md:376-394`                                          | なし                  |
| 同じ           | テストの DB だけ永続化の設定を切る                          | `about.md:58`                               | Testcontainers 2.0.5 の既定の `fsync=off` を上書きしない                              | なし                  |

`lock_timeout` について確かめたこと。

- PostgreSQL の `lock_timeout` の既定は 0（無効）である（ADR-055:26 が引く Client Connection Defaults）。
- Liquibase と jOOQ の生成は、`backend/gradle/database.gradle:25` の `migrationDatabaseUrl` で Gradle から接続し、HikariCP の `connection-init-sql` を通らない（`database.gradle:91-117,296`）。
- アプリは Liquibase を起動に含めない（`application.yaml:86`）。
- ADR-055 が `postgresql.conf` での設定を退けた理由（:102、長い DDL やバッチが止まる）は `statement_timeout` に当たる。`lock_timeout` はロックを取るまでの待ちだけを止める。
- `CREATE INDEX CONCURRENTLY` が途中で失敗すると無効なインデックスが残る（PostgreSQL の CREATE INDEX の文書）。Runbook に消し方が要る。

### I/F と非同期

| 判定           | 項目                                                      | 向こう                                                                                    | こちら                                                                                                                                   | 扱い                    |
| :------------- | :-------------------------------------------------------- | :---------------------------------------------------------------------------------------- | :--------------------------------------------------------------------------------------------------------------------------------------- | :---------------------- |
| ない           | 業務のキーで 1 つに決まるファイルを条件付き書き込みで置く | `docs/interface/about.md:42`、`data-consistency.md:106,112`、`object-key-naming.md:46-58` | `docs/integration/interface-data-consistency.md:45-52`、`interface-object-storage-layout.md:46-50`、`interface-runbook-correction.md:19` | #588                    |
| 同じ           | Outbox を自分の ADR に結びつける                          | `docs/async/about.md:32`                                                                  | `docs/integration/async-messaging-design.md:25-26,71-85`                                                                                 | なし                    |
| 違う           | 受け手の写しを版で比べて古いイベントを捨てる              | `docs/async/about.md:33`                                                                  | ADR-050:200                                                                                                                              | #561（#312 で決定済み） |
| ない           | イベントの契約（版、追加だけ、fixture）                   | `docs/async/about.md:34`、`event-contract.md`                                             | `docs/backend/class-roles/event.md:103-106`                                                                                              | #560                    |
| 当てはまらない | 標準キューと冪等なコンシューマー                          | `docs/async/about.md:35-36`                                                               | SQS を使わない                                                                                                                           | #313 で決定済み         |
| 違う           | DLQ を基盤の回数の上限だけにする                          | `docs/async/about.md:37`                                                                  | `docs/integration/async-failure-recovery.md:101-115`                                                                                     | #559、#562              |

条件付き書き込みは、S3 の User Guide（How to prevent object overwrites with conditional writes）で次を確かめた。

- `If-None-Match: *` は PutObject、CompleteMultipartUpload、CopyObject で使え、権限は `s3:PutObject` だけで足りる。
- 同じキーへの同時の書き込みは、最初に終わった 1 つだけが成功し、残りは `412` になる。
- バージョニングのバケットでは今の版だけを見るので、削除マーカーなら書き込みは通る。
- 同時の削除が先に終わると `409` になる。
- バケットポリシーで条件付き書き込みを強制できる。

こちらは原文の形のままで、`interface-job-ordering.md:10` が求める冪等なジョブと、すべてのファイルにタイムスタンプを付ける命名（`interface-object-storage-layout.md:46-50`）が両立しない。
こちらのコードに S3 の利用はなく、変えるのは docs だけである。

### ログ

| 判定           | 項目                                       | 向こう                                  | こちら                                                                        | 扱い                |
| :------------- | :----------------------------------------- | :-------------------------------------- | :---------------------------------------------------------------------------- | :------------------ |
| 同じ           | フロントの `console` を lint で止める      | `about.md:29`                           | `docs/observability/log-output-points.md:69-70`、`frontend/vite.config.ts:95` | なし                |
| 同じ           | `http.server.request.duration` を秒にする  | `about.md:30,33`                        | `log-output-points.md:46`                                                     | なし                |
| 同じ           | DB のキーを今の semconv の名前にする       | `about.md:31`                           | ADR-070:40、`conventions.md:57,68`                                            | なし                |
| 当てはまらない | `db.client.operation.duration`             | `about.md:32`                           | ログに DB のキーを持たない                                                    | #320                |
| 同じ           | タイムスタンプを UTC の `Z` だけにする     | `about.md:34`                           | Spring Boot の ECS の formatter が `Instant` を出す                           | なし                |
| 同じ           | `exception.stacktrace`                     | `about.md:35`                           | `conventions.md:32`、`docker/otel-collector/config.yaml:37`                   | なし                |
| 違う           | Web Vitals をログにしない                  | `about.md:36`                           | ADR-068（Faro が log record で送る）                                          | なし                |
| 当てはまらない | `severity.text` と `level` の併記          | `about.md:37`                           | Lambda に置かない                                                             | #321 で見送り       |
| 違う           | `http.request.id`                          | `about.md:38`                           | trace ID が正本（`conventions.md:46`）                                        | #321 で見送り       |
| 同じ           | `user.id`                                  | `about.md:38`                           | `log-output-points.md:41`                                                     | #378                |
| 当てはまらない | `trace_flags`                              | `about.md:39`                           | OTLP の LogRecord が持つ                                                      | #321 で見送り       |
| ない           | `tenant_id`                                | `about.md:39`                           | なし                                                                          | #378（`tenant.id`） |
| 違う           | `event.code`（メッセージコード）           | `about.md:39`                           | event 名で識別（`log-operational-events.md:24-26`）                           | #321 で見送り       |
| 当てはまらない | `event.category` の例を直す                | `about.md:39`                           | ECS の `event.*` を使わない                                                   | なし                |
| ない           | 401、403、400 の検証の失敗を理由つきで残す | `about.md:40`、`web-api.md:70-80`       | `ApiExceptionHandler.java:226-227` は status だけ                             | #590（401 と 403）  |
| ない           | メッセージの定義とコードのずれを検査する   | `about.md:41`、`message-codes.md:46-54` | `log-operational-events.md:24-33` に検査がない                                | #590                |
| 同じ           | マスクを 3 層にする                        | `about.md:42`                           | `conventions.md:50-66,103-112,182`（2 層目は Collector の allowlist）         | #321                |

確かめたこと。

- `ApiExceptionHandler.java:63-79` は、CSRF のトークンの失敗、テナントを選ぶ前の拒否、権限の不足を別の Problem にするが、ログ（`:226-227`）はどれも `API client error` と status だけになる。
- `exception.type` は Collector の `keep_keys`（`docker/otel-collector/config.yaml:37`）にすでにある。
- `backend/src/main/java` で WARN か ERROR を出す呼び出しは 10 か所ある（`EventPublicationResubmitOnce.java:152` は `atLevel` で結果からレベルを選ぶ）。`PaymentGatewayClient.java:135-138,173-177` の 2 つだけが message に `{}` を埋め込み、Runbook に見出しがない。ほかの 8 つの WARN 以上の event 名には、`docs/observability/runbook-*.md` に `## <event 名>` の見出しがある。

400 の検証の失敗を INFO にする向こうの形は取り込まない。
こちらは SPA が先に検証するので、API の 400 を SPA と API の制約の不一致として WARN で通知し、直す（`docs/observability/runbook-api-errors.md` の「API validation failed」）。
向こうが避けたい攻撃のときの通知の鳴り続けは、こちらでも起きうる。#346 のログインの rate limit と合わせて見直す余地がある（判断）。

### Web フロントエンド

| 判定           | 項目                                   | 向こう                                    | こちら                                               | 扱い                        |
| :------------- | :------------------------------------- | :---------------------------------------- | :--------------------------------------------------- | :-------------------------- |
| 同じ           | 静的解析を Vite+ と lefthook に寄せる  | `about.md:30`                             | `docs/frontend/lint-config.md:10-17`、ADR-034        | なし                        |
| 同じ           | XSS の入口を lint で止める             | `about.md:31`                             | `frontend/vite.config.ts:4-28,162,171-173`           | なし                        |
| ない           | disable のコメントに理由を必須にする   | `about.md:31`、`static-analysis.md:73,87` | 書き方は定着しているが検査がない                     | #591                        |
| ない           | スマホの幅（新しい文書 `mobile.md`）   | `about.md:32`                             | `docs/frontend/screen-size-and-responsive.md`        | #337                        |
| 同じ           | `aria-errormessage` と `aria-invalid`  | `about.md:33`                             | `docs/frontend/form-validation.md:63`                | なし                        |
| 同じ           | AVIF と APNG                           | `about.md:34`                             | `docs/frontend/images-and-icons.md:16-19`            | なし                        |
| 同じ           | Cookie の期限を 400 日に直す           | `about.md:35`                             | `docs/frontend/dark-mode.md:24-26`（期限を書かない） | #343                        |
| 同じ           | ブラウザだけの OIDC を勧めない         | `about.md:36`                             | ADR-007:21-30                                        | なし                        |
| 同じ           | SVG の favicon                         | `about.md:37`                             | `images-and-icons.md:33`                             | なし                        |
| 同じ           | `react` と `jsx-a11y` の plugin        | `about.md:38`                             | `vite.config.ts:97-107`                              | #335                        |
| 同じ           | `react/no-array-index-key`             | `about.md:39`                             | perf の category を error（`vite.config.ts:93`）     | なし                        |
| 違う           | Effect の規則                          | `about.md:39`                             | Effect を全面に禁止（ADR-088）                       | なし                        |
| ない           | shadcn の部品の上流の更新の取り込み方  | `about.md:40`、`component-design.md:92`   | `docs/frontend/ui-and-style.md:57-78` は追加だけ     | #591                        |
| 違う           | Base UI の `CSPProvider`               | `component-design.md:93`                  | ADR-030:33-42                                        | なし                        |
| 当てはまらない | Storybook の story で component test   | `component-design.md:94`                  | Storybook を入れない                                 | #284 で見送り（#253）、#335 |
| 違う           | E2E を必須のチェックにする             | `about.md:41`                             | ADR-057:54-56                                        | なし                        |
| 違う           | 表示のテストを America/New_York で流す | `testing.md:134-136`                      | ADR-047                                              | なし                        |

`screen-size-and-responsive.md:22` は、想定の解像度を下回ったらはみ出した部分をスクロールバーで出すとする。
WCAG 2.2 の 1.4.10（AA）は、幅 320 CSS px で 2 方向のスクロールを求めないことを定め、`docs/frontend/component-design.md:54` は WCAG に従うとするので、2 つの文書が食い違う。
この食い違いは #337 に渡す。

oxlint 1.93（vite-plus 1.6.0）の `oxlint --rules` に、disable のコメントに理由を求める組み込みの規則はない。
近いのは `unicorn/no-abusive-eslint-disable` だけである。
`frontend/apps` と `frontend/packages` の disable のコメント 37 件（生成物の `routeTree.gen.ts` を除く）のうち、同じ行に `-- <理由>` がないのは 10 件である。
前の行に理由を書くもの（`frontend/apps/web/src/routes/_authenticated/_tenant/orders/$orderId/index.tsx:19-20` など）と、理由がないもの（`frontend/apps/web/e2e/orders.ts:30`）がある。

### そのほかの領域

| 判定     | 項目                                                                       | 向こう                                                    | こちら                                                                     | 扱い                    |
| :------- | :------------------------------------------------------------------------- | :-------------------------------------------------------- | :------------------------------------------------------------------------- | :---------------------- |
| 同じ     | 適応度関数の例を自分の道具に置き換える                                     | `architecture-principles/about.md:31`                     | `docs/principles/architecture-fitness-functions.md:20-21`                  | なし                    |
| 同じ     | レビューの前提の lint と整形を自分の道具にする                             | `code-review/about.md:31`                                 | `docs/code-review/review-scope.md:26-30`                                   | なし                    |
| 同じ     | Git ブランチフローをトランクベースにする                                   | `code-review/about.md:32-33`                              | ADR-017、ADR-040:30                                                        | なし                    |
| 同じ     | 暗黙知の落とし先に docs と AGENTS.md とスキルを入れる                      | `code-review/about.md:34`                                 | `review-scope.md:35-42`、`docs/principles/continuous-reinforcement.md`     | なし                    |
| ない     | エージェントの PR で人が判断すること、エージェントのレビューを承認にしない | `code-review/about.md:35`、`agent-pull-requests.md:24-38` | `docs/pull-request/review-request-and-merge.md:41-59` は読む PR の条件だけ | #592                    |
| 同じ     | CODEOWNERS の設定                                                          | `code-review/about.md:36`                                 | `docs/repository/branch-protection.md:59-65`                               | なし                    |
| 決定済み | 必須のチェックの `dependency-review`                                       | `code-review/about.md:37`                                 | Trivy と dep-scan は失敗にしない（ADR-095）                                | 取り込まない            |
| 決定済み | テストを弱めた PR の警告                                                   | `code-review/about.md:38`                                 | `review-request-and-merge.md:51`                                           | #423（#265 で決定済み） |
| 同じ     | 非機能要件の書籍の脚注を本文にする                                         | `non-functional-requirements/about.md:32`                 | `docs/nfr/frameworks.md:23`                                                | なし                    |
| 同じ     | TTFB と Core Web Vitals の図を文章にする                                   | `performance-test/about.md:30`                            | `docs/performance-test/frontend-performance-test.md:11,26-40`              | なし                    |
| 同じ     | 空のリンクを直す                                                           | `technical-writing/about.md:32`                           | 写していない                                                               | なし                    |
| 同じ     | `RFC 2199` を `RFC 2119` に直す                                            | `technical-writing/about.md:33`                           | RFC を引いていない                                                         | なし                    |

帳票（report）の変更点は定型の項目だけだった。
原文の誤り（`RFC 2199`、行き先が空のリンク）は `e309a6d` の `forTechnicalWriting/technical_writing_guidelines.md` の 783、791、941、943 行にあることを確かめ、こちらには写っていないことを確かめた。

`dependency-review` を取り込まない理由。

- 依存を足す PR は、すでに人が必ず読む条件に入っている（`review-request-and-merge.md:50`）。
- GitHub の依存グラフは Gradle を静的に読まず、dependency submission が要る。
- PR が新しく持ち込んだ指摘で止める形は #275 で比べ、止める対象がなかったので見送った。依存の脆弱性も advisory の公開で main に現れるので、同じ理由が当たる。

## ほかで見つけたこと

- `docs/observability/log-output-points.md:30-55` が求めるアクセスログは、`backend/src/main` に実装がない。#321 のコメントも指摘しているが、Issue はない。
- `TableWriterConcurrencyTest.java:305-319` は、待ちを `sleep(10)` のループで手で書く。ADR-080 は期限付きの条件の待ちを Awaitility で書くと決めている。
- `docs/observability/conventions.md:54` と ADR-015:61 は「利用者へ結び付く ID」を渡さない値とし、`log-output-points.md:41` は `user.id` を記録するとする。#378 が ADR-015 を改めるので、そこで直る。
- `docs/integration/async-failure-recovery.md:35,72,106` の回復できないエラーの扱いが食い違う。#559 が直す。

## 出典

- 向こう：`docs/web-api/about.md`、`docs/db/about.md`、`docs/interface/about.md`、`docs/async/about.md`、`docs/logging/about.md`、`docs/web-frontend/about.md`、`docs/architecture-principles/about.md`、`docs/code-review/about.md`、`docs/code-review/agent-pull-requests.md`、`docs/non-functional-requirements/about.md`、`docs/performance-test/about.md`、`docs/report/about.md`、`docs/technical-writing/about.md` と、各項目が変えた文書（commit `29ba897`）
- 原文：[future-architect/arch-guidelines](https://github.com/future-architect/arch-guidelines)（`e309a6d` と、その後の commit）
- [RFC 9112](https://www.rfc-editor.org/rfc/rfc9112) の 3、[RFC 10017](https://www.rfc-editor.org/rfc/rfc10017) の 6.1.3.2、6.1.3.3
- [draft-ietf-httpapi-idempotency-key-header-08](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/)
- [Amazon S3 User Guide: How to prevent object overwrites with conditional writes](https://docs.aws.amazon.com/AmazonS3/latest/userguide/conditional-writes.html)
- [PostgreSQL 18: Client Connection Defaults](https://www.postgresql.org/docs/18/runtime-config-client.html)、[CREATE INDEX](https://www.postgresql.org/docs/18/sql-createindex.html)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [WCAG 2.2: 1.4.10 Reflow](https://www.w3.org/TR/WCAG22/#reflow)
- [GitHub Docs: Review AI-generated code](https://docs.github.com/en/copilot/tutorials/review-ai-generated-code)
- こちら：ADR-003、ADR-004、ADR-005、ADR-007、ADR-013、ADR-014、ADR-015、ADR-016、ADR-030、ADR-040、ADR-047、ADR-054、ADR-055、ADR-057、ADR-060、ADR-062、ADR-066、ADR-068、ADR-080、ADR-086、ADR-088、ADR-095 と、上の表の docs とコード
