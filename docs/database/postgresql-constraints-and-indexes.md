---
type: Convention
title: PostgreSQLの制約とインデックス
description: 主キー、外部キーを作る範囲、NOT NULL、CHECK、一意性（業務テーブルのユニークインデックスの先頭の列を含む）、排他制約の使い分けと、インデックスの種類と設計の方針を定める規約。テーブルに制約やインデックスを追加するとき、表どうしの参照を足すとき、性能対策でインデックスを見直すときに読む。
tags: [convention, database, postgresql, constraints, index, future-arch-guidelines]
---

# PostgreSQLの制約とインデックス

NOT NULLはできる限り付け、一意性はユニーク制約ではなくユニークインデックスで表す。
外部キー制約は同じモジュールの表どうしの参照にだけ作り、CHECK制約は作らず、期間の重なりは排他制約で防ぐ。
インデックスはB-treeだけを使い、必要最小限に保つ。

## 対象

この規約は、モジュールが所有する業務テーブルに適用する。
Spring Modulithのイベント出版テーブルのように、フレームワークがスキーマを定めるテーブルには適用しない。

## 主キー

主キーはデータモデルに合わせて設定する。
サロゲートキーとナチュラルキーの選び方は[PostgreSQLの主キー](postgresql-primary-keys.md)に従う。

## 外部キー

外部キー制約は、同じモジュールの表どうしの参照にだけ作る。
理由は[ADR-108](../adr/ADR-108-add-tenant-composite-foreign-keys-within-modules.md)に示す。

- **業務テーブルどうしの参照**：`(tenant_public_id, 参照先の主キー)`の複合の外部キーにし、参照先に同じ組のユニークインデックスを作る（`ordering.t_order_line`から`ordering.t_order`）。
  業務テーブルの範囲は[PostgreSQLのマルチテナント設計](postgresql-multitenancy.md#rlsでテナントを分ける業務テーブル)に示す。
- **`tenancy`の表どうしの参照**：RLSの外のスキーマなので、参照先の主キーだけの単独の列の外部キーにする。
  今の該当は、`tenancy.m_user_identity.user_id`と`tenancy.m_membership.user_id`から`tenancy.m_user.user_id`、`tenancy.m_membership.tenant_id`から`tenancy.m_tenant.tenant_id`である。
- **モジュールをまたぐ参照**：外部キー（スキーマをまたぐ外部キー）を作らない。
  論理設計のER図に書き、参照の整合性はアプリケーションで保つ。
  別のモジュールの行は`<対象>_public_id`の`uuid`の列で指す（[PostgreSQLの主キー](postgresql-primary-keys.md)）。
  理由は[ADR-089](../adr/ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)に示す。

同じスキーマのほかの表の主キーと同じ名前の列（`t_order_line`の`order_id`など）は、その表への参照とみなし、外部キーを作る。

外部キーは、changesetの`addForeignKeyConstraint`で作る。
列の`constraints`には書かず、`onDelete`、`onUpdate`、`deleteCascade`を付けない。
参照先の行を消したときと変えたときの動作は、既定の`NO ACTION`になる。
名前は[PostgreSQLの命名規約](postgresql-naming.md#外部キー名とポリシー名)に従う。

外部キーは次の検査で確かめる。
規則の記号の定義は[バックエンドのアーキテクチャテスト](../backend/architecture-tests.md)にある。

- **カタログのテスト**：`SchemaConventionsTest`と`SchemaInspectionTest`が、同じモジュールの表を指す列に外部キーがあることと、外部キーの列が業務テーブルなら複合、`tenancy`の表なら単独の形であることを確かめる（規則K1）。履歴の表（`h_`）は、削除した行を写して残すため、K1とP3の判定から外す（[ADR-131](../adr/ADR-131-archive-removed-child-rows-in-table-writer.md)）。
  規則P4は、別のスキーマのテーブルを参照する外部キーを拒否する。
- **changesetのlint**：`ChangesetLintTest`が、外部キーを`addForeignKeyConstraint`で作ること、名前、参照先のスキーマと列、`onDelete`と`onUpdate`がないことを確かめる（規則K4）。

## NOT NULL

カラムはできる限りNOT NULLにする。
値がないことを表したいときは、区分値に「不明」や「適用不能」の値を用意できないか検討する。

下書きや仮登録のように入力途中の状態を保存するためにNOT NULLを外したくなったら、入力途中のデータをワークテーブルへ分け、本体のテーブルをNOT NULLに保てないか先に検討する。

## CHECK制約

CHECK制約は使わない。
値の範囲や形式の検証はアプリケーションで行う。
制約の変更にテーブルロックが必要になり、検証の内容はアプリケーションのリリースで変える方が容易なためである。

稼働中のテーブルへNOT NULLを追加する手順で一時的に使うCHECK制約は例外とし、[PostgreSQLのロックを抑えるスキーマ変更](postgresql-online-schema-change.md)に従う。

## 一意性

一意性はユニーク制約ではなく、明示的に作るユニークインデックスで表す。
Liquibaseでは`addUniqueConstraint`ではなく、`unique: true`を付けた`createIndex`を使う。

業務テーブル（[PostgreSQLのマルチテナント設計](postgresql-multitenancy.md#rlsでテナントを分ける業務テーブル)）のユニークインデックスは、すべて`tenant_public_id`から始める。
先頭の列はカタログのテスト（`SchemaConventionsTest`と`SchemaInspectionTest`の規則S6）が確かめる。
理由は[ADR-107](../adr/ADR-107-isolate-tenants-with-rls-set-per-transaction.md)に示す。

- ユニークインデックスはNULLの重複を許す。NULLの重複を禁止するときは`NULLS NOT DISTINCT`を付ける。
- 文字列のカラムを空文字の既定値にした場合、空文字は重複として扱われる。
- パーティションテーブルのユニークインデックスにはパーティションキーを含める。
- ISBNのように再利用されうるナチュラルキーにはユニークインデックスを作れないため、UNIQUEを外したインデックスにする。

## 排他制約

適用開始日と適用終了日を持つテーブルでは、排他制約で期間の重なりを防ぐ。
スカラー値の等価条件と範囲の重なりを組み合わせるため、`btree_gist`拡張が必要になる。
適用期間付きデータの設計は[PostgreSQLの時間で変わる業務データ](postgresql-temporal-data.md)に従う。

空間データの排他制約は、機能要件に応じて導入を検討する。

## インデックスの種類

インデックスは`btree`だけを使い、`hash`、GiST、SP-GiST、GIN、BRIN、bloomを使わない。
業務処理のほとんどは範囲検索か並べ替えを伴い、`hash`はそれに使えないためである。

排他制約に必要なGiSTと、全文検索に必要なGINは例外とする。
全文検索のインデックスは[PostgreSQLのサーバー設定と拡張機能](postgresql-server-configuration.md)に従う。

## インデックスの設計

インデックスは書き込みの性能を下げるため、必要最小限にする。
使われていないインデックスは定期的に確認して削除する。
リードレプリカがある場合は、プライマリとレプリカの両方で使われていないことを確認する。

最初は単純な構成にし、次のインデックスは性能対策が必要になった時点で使う。

- **複数列インデックス**：使ってよい。4列以上にするときは、理由をPull Requestに書いてレビューで合意する。パーティションキーは列に含めなくてよい。
- **式インデックス**：原則として使わない。式の結果をカラムに持ち、そのカラムにインデックスを張る。リリース直前で改修が難しい場合に限り、最後の手段として使い、理由をchangesetの`comment`に書く。
- **部分インデックス**：最初からは使わない。性能対策が必要になったときに、データのカーディナリティと対象のクエリを確認し、レビューで合意してから使う。
- **カバリングインデックスと`INCLUDE`付きインデックス**：原則として使わない。厳しい応答要件を他の手段で満たせない場合に限り、性能対策の最後の手段としてレビューで合意してから使う。

PostgreSQLには複数パーティションにまたがるグローバルインデックスがなく、パーティションテーブルのインデックスはパーティションごとに作られる。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
