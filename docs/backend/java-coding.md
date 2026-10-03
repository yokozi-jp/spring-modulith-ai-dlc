---
type: Convention
title: バックエンドの Java 実装規約
description: 手書きのバックエンドコードで使う Lombok、record、Domain Model の状態変更、ロギング、依存の注入、例外、assert、非推奨の API、プロキシで動くアノテーションの規約と理由を定める。データキャリアやログ出力を書くとき、Bean の依存や例外を書くとき、これらの PMD や ArchUnit の規則で失敗したときに読む。
tags: [convention, backend, java, logging]
---

# バックエンドの Java 実装規約

Lombok の `@Data` と `@Setter` を使わず、データキャリアは record にし、Domain Model の状態は業務上の操作を表すメソッドで変更する。
ロギングは SLF4J facade と Lombok の `@Slf4j` に統一し、依存はコンストラクタで受け取り、具体的な例外を投げ、`assert` に詳細を書き、非推奨の API を使わない。
プロキシで動くアノテーションを付けたメソッドは、同じクラスの中から呼ばない。
これらの規則の検査方法は [バックエンドのアーキテクチャテスト](architecture-tests.md) に示す。

## 規約

手書きのバックエンドコードでは、Lombok の `@Data` と `@Setter` を使用しない。

次のデータキャリアには record を使う。

- モジュールルートの参照の結果、検索条件、イベント。
- 値オブジェクト。固定の値の集合は enum にする。
- Application の `<UseCase>Command` と `<UseCase>Result`。
- Presentation の `<UseCase>Request` と `<QueryResult>Response`。

Domain Model の状態は setter で公開せず、業務上の操作と不変条件を表すメソッドを介して変更する。

## ロギング

機能コードのロギングには SLF4J facade を使い、Logback、Log4j、Apache Commons Logging の実装 API を直接参照しない。

ログを出力する手書きクラスには Lombok の `@Slf4j` を付け、Logger フィールドと `LoggerFactory` を直接記述しない。

`@Slf4j` の使用規約はソース上の Logger 型と `LoggerFactory` 参照を PMD で禁止して強制する。

ベースパッケージ直下のロギング基盤設定は実装 API との接続を担うため、ArchUnit の facade 制約だけは対象外とする。

ログの書き方を SLF4J と `@Slf4j` の一通りにする。
SLF4J の key-value は、OpenTelemetry へ送るログの属性になる（[ADR-015](../adr/ADR-015-structure-and-protect-observability-data.md)）。
機能コードが SLF4J の API だけを使えば、ロギングの実装を差し替えても機能コードを変えずに済む。

## 依存の注入

Bean の依存はコンストラクタの引数で受け取り、フィールドとメソッドに `@Autowired` などの注入アノテーションを付けない。
コンストラクタが一つなら `@Autowired` を付けない。

依存をコンストラクタに明示すると、フィールドを `final` にでき、テストでは Spring を起動せずに組み立てられる。
Spring の文書もコンストラクタによる注入を勧めている（[Spring Framework, Constructor-based or setter-based DI?](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html#beans-constructor-vs-setter-injection)）。

## 例外の型

`Throwable`、`Exception`、`RuntimeException`、`Error` を投げず、`IllegalArgumentException` や `NoSuchElementException` などの具体的な例外を投げる。

呼び出し側と `error.presentation.web.ApiExceptionHandler` が例外の型で失敗を区別し、HTTP の応答に対応づけられるようにするためである（[ADR-013](../adr/ADR-013-standardize-http-api-contracts.md)）。

## assert 文のメッセージ

`assert` 文と `new AssertionError()` には、対象と値を示す詳細のメッセージを付ける。

失敗のログだけで原因と値を特定できるようにするためである。

## 非推奨の API

`@Deprecated` の API を使わず、その Javadoc が示す代わりの API を使う。

依存ライブラリの更新で API が削除されても、ビルドが壊れないようにするためである。
Dependabot の minor と patch の更新はまとめて取り込む（[ADR-021](../adr/ADR-021-group-dependabot-minor-and-patch-updates.md)）。

## プロキシで動くアノテーション

次のアノテーションを付けたメソッドは、同じクラスの中から呼ばない。

- Spring の `@Transactional`、`@Async`、`@Cacheable`、`@CachePut`、`@CacheEvict`。
- Spring Security の `@PreAuthorize`、`@PostAuthorize`、`@PreFilter`、`@PostFilter`、`@Secured`。
- Resilience4j の `@CircuitBreaker`、`@Retry`、`@RateLimiter`、`@Bulkhead`、`@TimeLimiter`。

Spring の AOP プロキシは、Bean の外からの呼び出しだけを横取りする。
同じクラスの中からの呼び出しはプロキシを通らないため、アノテーションは黙って効かない（[Spring Framework, Understanding AOP Proxies](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html#aop-understanding-aop-proxies)）。
トランザクションが始まらず、非同期にならず、キャッシュを通らず、認可を確かめず、Resilience4j のサーキットブレーカーやリトライが働かない。

アノテーションを付けたメソッドは別の Bean へ移し、その Bean を注入して外から呼ぶ。

いまのコードで使っていないアノテーションの規則も、検査の費用がほぼなく使い始めたときの事故を防げるため残す。
