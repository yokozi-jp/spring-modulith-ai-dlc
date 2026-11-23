---
type: Reference
title: テーブルごとの保持期限と個人情報の有無の一覧
description: 現在のテーブルごとに、保持期限、個人情報を含むかどうか、改廃の方法を並べた一覧。テーブルを追加または削除する changeset を書くとき、保持期限や個人情報の扱いを決めるとき、データの削除を実装するときに読む。
tags: [reference, database, postgresql, retention, personal-data]
---

# テーブルごとの保持期限と個人情報の有無の一覧

changeset が作る全テーブルについて、保持期限と個人情報の有無を書く。
保持期限を決められていないテーブルは「未定」と書き、決める Issue を併記する。
保持期限の方針と改廃の手順は[PostgreSQLのパーティションと改廃](postgresql-partitioning-and-retention.md)に従う。
設計書にこの情報を書く場所は[設計書の正本](../writing/design-doc-sources-of-truth.md)が定める。

テーブルを追加または削除する changeset は、同じ変更でこの一覧の行を足すか消す。
`SchemaInspectionTest`は、DB にあるテーブルがこの一覧に載っていることを確かめる。

## 保持期限と個人情報

個人情報は、個人情報保護法の個人情報と、IdP の識別子のように個人を識別できる符号を含む。
内部 ID だけを持つ列は、個人情報に数えない。

| テーブル                             | 種別   | 保持期限                                                                                 | 個人情報 | 根拠                                                                                                                   |
| ------------------------------------ | ------ | ---------------------------------------------------------------------------------------- | -------- | ---------------------------------------------------------------------------------------------------------------------- |
| `tenancy.m_tenant`                   | マスタ | 無期限                                                                                   | 含まない | テナントの名称だけを持つ。                                                                                             |
| `tenancy.m_user`                     | マスタ | 未定（[#672](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672)）           | 含まない | 内部 ID と公開 ID だけを持つ。                                                                                         |
| `tenancy.m_user_identity`            | マスタ | 未定（[#672](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672)）           | 含む     | IdP の発行者の URL と利用者の識別子（`sub`）を持つ。                                                                   |
| `tenancy.m_membership`               | マスタ | 未定（[#672](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/672)）           | 含まない | 利用者 ID、テナント ID、役割だけを持つ。                                                                               |
| `product.m_product`                  | マスタ | 無期限                                                                                   | 含まない | 商品の情報だけを持つ。                                                                                                 |
| `ordering.t_order`                   | トラン | 未定（[#671](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/671)）           | 含まない | 注文コード、状態、注文日時だけを持つ。顧客の氏名と連絡先の列はない。                                                   |
| `ordering.t_order_line`              | トラン | 未定（[#671](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/671)）           | 含まない | 商品、数量、単価だけを持つ。                                                                                           |
| `payment.t_payment`                  | トラン | 未定（[#671](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/671)）           | 含まない | 金額、状態、決済代行の決済コードだけを持つ。カード番号は持たない。                                                     |
| `modulith.event_publication`         | ワーク | 完了するまで。完了すると archive へ移る                                                  | 含まない | イベントに個人情報を載せない規約に従う（[非同期メッセージのセキュリティ](../integration/async-message-security.md)）。 |
| `modulith.event_publication_archive` | ワーク | 7 日（[#542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542)で実装する） | 含まない | `event_publication` と同じ。                                                                                           |
| `modulith.shedlock`                  | ワーク | 無期限（ジョブごとに 1 行を更新し続ける）                                                | 含まない | ロックの名前と時刻、取得したインスタンスの名前だけを持つ。                                                             |
| `modulith.spring_session`            | ワーク | セッションの有効期限まで                                                                 | 含まない | セッション ID と、アプリの利用者の ID（`principal_name`）を持つ。                                                      |
| `modulith.spring_session_attributes` | ワーク | セッションの有効期限まで（`spring_session` の削除で連鎖して消える）                      | 含む     | 直列化した `OidcUser` を持つ。ID トークンの claim に、氏名やメールアドレスが入りうる。                                 |

## 改廃の方法

改廃を実行するテーブルだけを書く。
ここにないテーブルは、改廃の仕組みがまだない。

| テーブル                             | 改廃方法             | 自動化                                                                                                                          | 実行のタイミング                  | 改廃の条件               | `ANALYZE` と `VACUUM`  |
| ------------------------------------ | -------------------- | ------------------------------------------------------------------------------------------------------------------------------- | --------------------------------- | ------------------------ | ---------------------- |
| `modulith.event_publication_archive` | DELETE               | あり（[#542](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/542)で実装する）                                        | 1 日 1 回                         | 完了から 7 日を過ぎた行  | 自動バキュームに任せる |
| `modulith.spring_session`            | DELETE               | あり（Spring Session JDBC の定期削除。[ADR-111](../adr/ADR-111-store-sessions-in-postgresql-until-user-growth-needs-redis.md)） | 各インスタンスで毎分              | `expiry_time` を過ぎた行 | 自動バキュームに任せる |
| `modulith.spring_session_attributes` | 外部キーのカスケード | あり                                                                                                                            | `spring_session` の行の削除と同時 | 親の行の削除             | 自動バキュームに任せる |
