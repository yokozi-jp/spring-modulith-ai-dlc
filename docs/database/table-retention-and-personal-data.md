---
type: Reference
title: テーブルごとの保持期限と個人データの有無の一覧
description: 現在のテーブルごとに、保持期限、個人データを含むかどうか、改廃の方法を並べた一覧。テーブルを追加または削除する changeset を書くとき、保持期限や個人データの扱いを決めるとき、データの削除を実装するときに読む。
tags: [reference, database, postgresql, retention, personal-data]
---

# テーブルごとの保持期限と個人データの有無の一覧

changeset が作る全テーブルについて、保持期限と個人データの有無を書く。
保持期限を決められていないテーブルは「未定」と書き、決める Issue を併記する。
保持期限の方針と改廃の手順は[PostgreSQLのパーティションと改廃](postgresql-partitioning-and-retention.md)に従う。
行を足す、消す時機は[DBマイグレーション規約](migrations.md)に従う。

## 保持期限と個人データ

個人データは、[PostgreSQLのテーブル論理設計](postgresql-logical-design.md#個人データ)の定義に従う。
アプリが採番した内部 ID は含めない。
内部 ID が `*_by` などの列に残る扱いは [#672] で決める。
個人データを含むテーブルは、含む列を括弧に書く。

| テーブル                             | 種別           | 保持期限                                                                                   | 個人データ                                       |
| ------------------------------------ | -------------- | ------------------------------------------------------------------------------------------ | ------------------------------------------------ |
| `tenancy.m_tenant`                   | マスタ         | 無期限                                                                                     | 含まない                                         |
| `tenancy.m_user`                     | マスタ         | 未定（[#672]）                                                                             | 含まない                                         |
| `tenancy.m_user_identity`            | マスタ         | 未定（[#672]）                                                                             | 含む（`issuer_url`、`subject_code`）             |
| `tenancy.m_membership`               | マスタ         | 未定（[#672]）                                                                             | 含まない                                         |
| `tenancy.t_idempotency_key`          | トラン         | `expires_at`（作成から 24 時間）まで（[ADR-126]）                                          | 含まない                                         |
| `tenancy.h_membership`               | 履歴           | 未定（[#672]）                                                                             | 含まない                                         |
| `tenancy.h_user_identity`            | 履歴           | 未定（[#672]）                                                                             | 含む（`issuer_url`、`subject_code`）             |
| `product.m_product`                  | マスタ         | 無期限                                                                                     | 含まない                                         |
| `product.t_idempotency_key`          | トラン         | `expires_at`（作成から 24 時間）まで（[ADR-126]）                                          | 含まない                                         |
| `ordering.t_idempotency_key`         | トラン         | `expires_at`（作成から 24 時間）まで（[ADR-126]）                                          | 含まない                                         |
| `ordering.t_order`                   | トラン         | 未定（[#671]）                                                                             | 含まない                                         |
| `ordering.t_order_line`              | トラン         | 未定（[#671]）                                                                             | 含まない                                         |
| `ordering.h_order_line`              | 履歴           | 未定（[#671]）                                                                             | 含まない                                         |
| `payment.t_payment`                  | トラン         | 未定（[#671]）                                                                             | 含まない                                         |
| `modulith.event_publication`         | フレームワーク | 完了するまで。完了すると archive へ移る                                                    | 含まない                                         |
| `modulith.event_publication_archive` | フレームワーク | `COMPLETED` は 7 日（[#542] で実装するまでは無期限）。`ABANDONED` は運用者が戻すか消すまで | 含まない                                         |
| `modulith.shedlock`                  | フレームワーク | 無期限（ジョブごとの 1 行を更新し続ける）                                                  | 含まない                                         |
| `modulith.spring_session`            | フレームワーク | セッションの有効期限まで                                                                   | 含まない                                         |
| `modulith.spring_session_attributes` | フレームワーク | セッションの有効期限まで                                                                   | 含む（`attribute_bytes`。直列化した `OidcUser`） |

`event_publication` の行は、イベントに個人データを載せない規約に従う（[非同期メッセージのセキュリティ](../integration/async-message-security.md)）。

## 改廃の方法

改廃を実行するテーブルと、実装を決めたテーブルだけを書く。
ここにないテーブルは、改廃の仕組みがまだない。

| テーブル                             | 改廃方法             | 自動化                                            | 実行のタイミング                                | 改廃の条件                                                                       | `ANALYZE` と `VACUUM`  |
| ------------------------------------ | -------------------- | ------------------------------------------------- | ----------------------------------------------- | -------------------------------------------------------------------------------- | ---------------------- |
| `tenancy.t_idempotency_key`          | DELETE               | あり（ShedLock の定期のジョブ。[ADR-126]）        | 定期（`IDEMPOTENCY_KEY_PURGE_INTERVAL` の間隔） | `expires_at` を過ぎた行。テナントごとに回さず、全テナントの行を消す（[ADR-126]） | 自動バキュームに任せる |
| `product.t_idempotency_key`          | DELETE               | あり（ShedLock の定期のジョブ。[ADR-126]）        | 定期（`IDEMPOTENCY_KEY_PURGE_INTERVAL` の間隔） | `expires_at` を過ぎた行。`tenancy.m_tenant` のテナントごとに消す（[ADR-126]）    | 自動バキュームに任せる |
| `ordering.t_idempotency_key`         | DELETE               | あり（ShedLock の定期のジョブ。[ADR-126]）        | 定期（`IDEMPOTENCY_KEY_PURGE_INTERVAL` の間隔） | `expires_at` を過ぎた行。`tenancy.m_tenant` のテナントごとに消す（[ADR-126]）    | 自動バキュームに任せる |
| `modulith.event_publication_archive` | DELETE               | なし（[#542] で実装する）                         | 1 日 1 回                                       | `COMPLETED` で完了から 7 日を過ぎた行。`ABANDONED` の行は消さない（[ADR-120]）   | 自動バキュームに任せる |
| `modulith.spring_session`            | DELETE               | あり（Spring Session JDBC の定期削除。[ADR-111]） | 各インスタンスで毎分                            | `expiry_time` を過ぎた行                                                         | 自動バキュームに任せる |
| `modulith.spring_session_attributes` | 外部キーのカスケード | あり                                              | `spring_session` の行の削除と同時               | 親の行の削除                                                                     | 自動バキュームに任せる |

冪等性キーの表の定期のジョブは、`IDEMPOTENCY_KEY_PURGE_ENABLED`が`true`のときだけ動き、1つのDELETEで`idempotency-key.purge.batch-size`の行まで消し、消し残しは同じ回で繰り返し消す。
ロックを取れたインスタンスだけが消し、実行の回数を`idempotency.key.purge.runs`（`outcome`は`completed`、`skipped`、`failed`）、消した行の数を`idempotency.key.purged`に数える。
1つの表やテナントの失敗で、後ろの表とテナントの掃除を止めず、回の最後に`failed`として数える。DBとロックの既知の失敗はWARN、予期しない例外はERRORで記録する。
`failed`が続くと期限切れの行が残るが、要求は期限を過ぎた行を新しいキーとして取り直すので、業務の結果は変わらない。
期限を過ぎて消える前の行は、要求が新しいキーとして取り直す（[HTTPメソッドの使い分け](../web-api/http-methods.md#再試行への備え)）。

[#542]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542
[#671]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/671
[#672]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672
[ADR-111]: ../adr/ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md
[ADR-120]: ../adr/ADR-120-keep-abandoned-event-publications-out-of-archive-retention.md
[ADR-126]: ../adr/ADR-126-require-idempotency-key-on-all-non-safe-business-apis.md
