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

| テーブル                             | 種別           | 保持期限                                  | 個人データ                                       |
| ------------------------------------ | -------------- | ----------------------------------------- | ------------------------------------------------ |
| `tenancy.m_tenant`                   | マスタ         | 無期限                                    | 含まない                                         |
| `tenancy.m_user`                     | マスタ         | 未定（[#672]）                            | 含まない                                         |
| `tenancy.m_user_identity`            | マスタ         | 未定（[#672]）                            | 含む（`issuer_url`、`subject_code`）             |
| `tenancy.m_membership`               | マスタ         | 未定（[#672]）                            | 含まない                                         |
| `product.m_product`                  | マスタ         | 無期限                                    | 含まない                                         |
| `ordering.t_order`                   | トラン         | 未定（[#671]）                            | 含まない                                         |
| `ordering.t_order_line`              | トラン         | 未定（[#671]）                            | 含まない                                         |
| `payment.t_payment`                  | トラン         | 未定（[#671]）                            | 含まない                                         |
| `modulith.event_publication`         | フレームワーク | 完了するまで。完了すると archive へ移る   | 含まない                                         |
| `modulith.event_publication_archive` | フレームワーク | 7 日（[#542] で実装するまでは無期限）     | 含まない                                         |
| `modulith.shedlock`                  | フレームワーク | 無期限（ジョブごとの 1 行を更新し続ける） | 含まない                                         |
| `modulith.spring_session`            | フレームワーク | セッションの有効期限まで                  | 含まない                                         |
| `modulith.spring_session_attributes` | フレームワーク | セッションの有効期限まで                  | 含む（`attribute_bytes`。直列化した `OidcUser`） |

`event_publication` の行は、イベントに個人データを載せない規約に従う（[非同期メッセージのセキュリティ](../integration/async-message-security.md)）。

## 改廃の方法

改廃を実行するテーブルと、実装を決めたテーブルだけを書く。
ここにないテーブルは、改廃の仕組みがまだない。

| テーブル                             | 改廃方法             | 自動化                                            | 実行のタイミング                  | 改廃の条件               | `ANALYZE` と `VACUUM`  |
| ------------------------------------ | -------------------- | ------------------------------------------------- | --------------------------------- | ------------------------ | ---------------------- |
| `modulith.event_publication_archive` | DELETE               | なし（[#542] で実装する）                         | 1 日 1 回                         | 完了から 7 日を過ぎた行  | 自動バキュームに任せる |
| `modulith.spring_session`            | DELETE               | あり（Spring Session JDBC の定期削除。[ADR-111]） | 各インスタンスで毎分              | `expiry_time` を過ぎた行 | 自動バキュームに任せる |
| `modulith.spring_session_attributes` | 外部キーのカスケード | あり                                              | `spring_session` の行の削除と同時 | 親の行の削除             | 自動バキュームに任せる |

[#542]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542
[#671]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/671
[#672]: https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672
[ADR-111]: ../adr/ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md
