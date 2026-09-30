---
type: Convention
title: 'jOOQコード生成と日時型'
description: jOOQコード生成の実行タイミング、生成コードのコミット、TIMESTAMPTZをInstantへ対応づける型マッピングを定める。changesetを追加してjOOQコードを再生成するとき、生成コードの日時型を確認するときに読む。
tags: [convention, database, jooq, datetime]
---

# jOOQコード生成と日時型

`TIMESTAMP WITH TIME ZONE`はforced typeで`java.time.Instant`へマッピングし、`OffsetDateTime`を持ち込みません。
changeset追加後は`migrateAndGenerateJooq`で生成し、生成コードはchangesetと同じ変更でコミットします。
本番では`jooqCodegen`と`migrateAndGenerateJooq`を実行しません。

## jOOQの日時型

jOOQコード生成には公式`org.jooq.jooq-codegen-gradle`プラグイン3.21.7を使用します。
PostgreSQLの`TIMESTAMP WITH TIME ZONE`と`TIMESTAMPTZ`は、forced typeによって`java.time.Instant`へマッピングします。
`OffsetDateTime`をアプリケーションの絶対時刻モデルへ持ち込みません。

`jooqCodegen`は現在のDBから生成するため、changeset追加後は単独実行せず`migrateAndGenerateJooq`を使用します。
通常のJavaコンパイルからコード生成を分離しているため、`compileJava`と`bootJar`はDBへ接続しません。

## 本番で生成しない理由

本番のDBマイグレーションジョブとアプリケーションデプロイでは、`jooqCodegen`と`migrateAndGenerateJooq`を実行しません。
生成コードはchangesetと同じ変更として`backend/src/generated/jooq`へコミットし、リリースビルドはそのコミット済みソースをコンパイルします。
本番でコード生成すると、リリース成果物が稼働DBの状態へ依存し、コード生成用のメタデータ参照権限も本番へ持ち込むことになります。
changeset追加時のコード生成とレビューを開発またはCIで完了させ、本番では`task be-release-migrate`によるマイグレーションと、コミット済み生成コードを含むアプリケーションのデプロイだけを実行します。
稼働DBの資格情報を開発端末へ配布してコード生成する運用も行いません。

生成に使うコマンドは[DB操作コマンドの早見表](commands.md)にあります。

## 参照資料

- jOOQ公式Gradleプラグイン: <https://github.com/jOOQ/jOOQ/tree/main/jOOQ-codegen-gradle>
