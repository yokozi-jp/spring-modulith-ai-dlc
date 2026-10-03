---
type: Convention
title: PostgreSQLの共通カラム
description: 全テーブルに付ける作成、更新、排他制御、データパッチの共通カラムと、その型と既定値、値の登録方法、アプリケーションからの参照の禁止を定める規約。業務テーブルを追加するとき、INSERTやUPDATEを実装するとき、データパッチを当てるときに読む。
tags: [convention, database, postgresql, audit-columns, future-arch-guidelines]
---

# PostgreSQLの共通カラム

業務テーブルには、作成、更新、排他制御、データパッチの共通カラムを例外なく付ける。
共通カラムは既定値を持たず、`patched_*`以外をNOT NULLにする。
共通カラムの値はアプリケーションがバインドして登録し、業務ロジックや画面表示に使わない。
データパッチでは`patched_*`だけを更新する。

## 対象

この規約は、モジュールが所有する業務テーブルに適用する。
Spring Modulithのイベント出版テーブルのように、フレームワークがスキーマを定めるテーブルには適用しない。

## カラム

| カラム                             | 型            | NULL     | 既定値 |
| ---------------------------------- | ------------- | -------- | ------ |
| `created_at`、`updated_at`         | `timestamptz` | NOT NULL | なし   |
| `created_by`、`updated_by`         | `varchar(64)` | NOT NULL | なし   |
| `created_pgm_cd`、`updated_pgm_cd` | `varchar(64)` | NOT NULL | なし   |
| `created_tx_id`、`updated_tx_id`   | `varchar(32)` | NOT NULL | なし   |
| `lock_no`                          | `bigint`      | NOT NULL | なし   |
| `patched_at`                       | `timestamptz` | NULL可   | なし   |
| `patched_by`                       | `varchar(64)` | NULL可   | なし   |
| `patched_id`                       | `integer`     | NULL可   | なし   |

- **`created_at`**：作成日時。
- **`created_by`**：作成者。利用者の操作では、OIDCのIDトークンの`sub`を登録する。利用者の操作でない処理では、`created_pgm_cd`と同じ値を登録する。
- **`created_pgm_cd`**：作成した機能やプログラムを一意に識別する値。`モジュール名.ユースケースのクラスの単純名`の形式にする（例：`order.PlaceOrder`）。
- **`created_tx_id`**：作成した処理の呼び出しを識別する値。Micrometer Tracingの現在のスパンのtrace IDを登録する。traceがなければ例外にし、登録を失敗させる。
- **`updated_at`**、**`updated_by`**、**`updated_pgm_cd`**、**`updated_tx_id`**：更新について、作成と同じ値を登録する。INSERTのときも登録する。
- **`lock_no`**：楽観的ロックのロック番号。INSERTのときは`1`を登録する。使い方は[PostgreSQLの排他制御](postgresql-concurrency-control.md)に従う。
- **`patched_at`**：データパッチの日時。パッチを当てていない行ではNULLにする。
- **`patched_by`**：データパッチの作業者を特定できる値。パッチを当てていない行ではNULLにする。
- **`patched_id`**：データパッチの課題番号。GitHub Issueの番号を`integer`で登録する。パッチを当てていない行ではNULLにする。

trace IDはログとの相関に使う識別子であり、[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)が定める。

## テーブルへの付与

- 共通カラムは全テーブルに付ける。
- 追記だけで更新しないテーブルにも、更新、排他制御、データパッチのカラムを付ける。

追記だけのワークテーブル（接頭辞`w_`）で、厳しい書き込み性能の要件を他の手段で満たせない場合に限り、更新とデータパッチのカラムを省いてよい。
省いたときは、理由をchangesetの`comment`に書く。
changesetの静的検査は、この`comment`があるかを確認する。

## 値の登録

- すべての共通カラムは、DBの既定値や`CURRENT_TIMESTAMP`に頼らず、アプリケーションがプレースホルダーでバインドする。`*_at`の現在時刻は[日時とタイムゾーンの規約](../datetime/timezone-conventions.md)のとおり`Clock`から取る。
- INSERTでは作成のカラムと更新のカラムの両方を登録する。
- 共通カラムの値はテストの検証対象にする。
- トリガーで共通カラムを登録しない。

## 既定値を持たない理由

- **`*_at`**：`Clock`以外から取った時刻はテストで固定できない。`CURRENT_TIMESTAMP`はトランザクションの開始時刻であり、アプリケーションの時刻と一致しない。
- **`*_by`、`*_pgm_cd`、`*_tx_id`**：`DEFAULT ''`にすると、値の登録漏れが空文字のまま成功し、気付けない。既定値のない`NOT NULL`なら、登録漏れのあるINSERTが失敗する。
- **`patched_*`**：パッチを当てていない行の値はNULLと決まっている。
- **`lock_no`**：すべての共通カラムをアプリケーションがバインドする方式に、例外を作らない。

## アプリケーションからの参照

共通カラムは運用の調査、障害対応、データ移行のために使い、業務ロジックで参照しない。

- 画面に「最終更新日時」や「最終更新者」を表示する要件があれば、`updated_at`や`updated_by`を使わず、業務用のカラムを別に定義する。
- 「直近5営業日に変更された商品」のような検索条件にも共通カラムを使わず、業務用のカラムを定義する。

排他制御の`lock_no`は例外であり、楽観的ロックで参照する。

## データパッチ

データパッチでは`patched_at`、`patched_by`、`patched_id`だけを更新し、`updated_*`と`lock_no`を更新しない。
システムによる更新とデータパッチによる更新を区別して記録するためである。

## 出典

- フューチャー株式会社「PostgreSQL設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forDB/postgresql_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
