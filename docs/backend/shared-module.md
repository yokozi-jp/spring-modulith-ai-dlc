---
type: Architecture
title: 共有モジュール shared
description: 機能モジュールではない技術的な共有モジュール com.example.demo.shared に置くパッケージと型、各パッケージを使ってよい層、ArchUnit の検査と検査できない AOP の呼び出しを説明する。shared に型を足すとき、shared の型を使ってよい層を確かめるとき、shared の使う場所の検査が失敗したときに読む。
tags: [architecture, backend, spring-modulith, shared-module]
---

# 共有モジュール shared

`com.example.demo.shared` は、機能モジュールではない唯一の技術的な共有モジュールである。
`@Modulithic(sharedModules = "shared")` で Spring Modulith の shared モジュールにし、決定は [ADR-048](../adr/ADR-048-add-shared-module-for-jooq-common-code.md) に示す。

- jOOQ の共通処理を `shared.persistence` に置き、このパッケージを `@NamedInterface` で公開する。
  業務テーブルの INSERT と UPDATE を組み立てて実行する唯一の入口である `TableWriter` もここに置く（[ADR-054](../adr/ADR-054-detect-optimistic-lock-conflicts-by-update-count.md)）。
- Spring Modulith のイベント出版のレジストリの、失敗した出版の定期の再投入（`EventPublicationResubmitter`）と手での 1 回の再投入（`EventPublicationResubmitOnce`）は、両方が使う掃き取り（`FailedPublicationSweep`）とともに `shared.eventresubmission` に置く（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
  レジストリは全モジュールが共有する基盤で業務の概念を持たず、jOOQ を使う処理は Persistence Adapter の層に置く規則があり、`shared.eventresubmission` はその層に入るためである。
  `EventPublicationResubmitOnce` 以外のクラスは package-private にし、他のモジュールから使えないようにする。
  `EventPublicationResubmitOnce` は、他のモジュールの結合テストがリスナーの回復を確かめるため public にする。
- 楽観的ロックの語彙（`ConflictException`、`ExpectedLockNo`、`VersionedCommand`）を `shared.concurrency` に置き、このパッケージも `@NamedInterface("concurrency")` で公開する。
- 業務上の失敗の例外（`NotFoundException`、`BusinessRuleViolationException`）を `shared.failure` に置き、このパッケージも `@NamedInterface("failure")` で公開する（[ADR-062](../adr/ADR-062-map-business-exceptions-to-404-409-422.md)）。
  `shared` は Spring Web、Spring の HTTP、Servlet の型に依存しない。
  `shared` は Domain と `error` の型、ルートパッケージの型に依存しない。
- リスナーが失敗した出版の再投入の方針を選ぶ注釈（`ResubmissionPolicy`）を `shared.events` に置き、このパッケージも `@NamedInterface("events")` で公開する（[ADR-079](../adr/ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)）。
- 共通カラムの `*_by` に登録するアプリの利用者を表す認証の principal（`AppUserPrincipal`）を `shared.security` に置き、このパッケージも `@NamedInterface("security")` で公開する（[ADR-096](../adr/ADR-096-require-app-user-principal-for-audit-columns.md)）。
  テナントを選ぶ前の拒否を表す `TenantNotSelectedException` もここに置き、`tenancy` が投げて `error` が専用の type の 403 にする（[ADR-101](../adr/ADR-101-keep-tenants-and-roles-in-app-database.md)）。
  この例外はテナントの ID も役割も持たず、ログインの状態（テナントをまだ選んでいないこと）だけを表すため、`AppUserPrincipal` と同じく認証の語彙として扱い、下の「業務の概念を置かない」の業務の概念に含めない。
  テナント、所属、役割の値と規則は `tenancy` に置く。
- 操作しているテナントの語彙を `shared.tenancy` に置き、このパッケージも `@NamedInterface("tenancy")` で公開する（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
  置く型は、テナントの `public_id` を包む `TenantId`、テナントを運ぶイベントの型 `TenantScoped`、呼び出しの間だけテナントを `ScopedValue` に束縛する `TenantContext` である。
  `TenantId` は `tenancy` の `domain.model` も使い、テナントの ID を表す型を 1 つにする。
  `TenantContext.call` で束縛を切り替えてよいのは、リクエストの filter（`tenancy` の `TenantSelectionFilter`）、`TenantListenerAspect`、`shared.batch` の `TenantPartitionAspect` だけである。
  テナントの値と規則は `tenancy` に置き、`shared.tenancy` は、どのテナントを操作しているかを運ぶ語彙と、リスナーでテナントを束縛する `TenantListenerAspect` だけを持つ。
- 業務のバッチの追いつきの Runner（`CatchUpJobRunner`）、partition のテナントを束縛する Aspect（`TenantPartitionAspect`）、Job の期間の規則（`JobPeriodRule`）を `shared.batch` に置き、このパッケージも `@NamedInterface("batch")` で公開する（[ADR-115](../adr/ADR-115-build-business-batches-with-spring-batch.md)）。
  `JobPeriodRule` 以外のクラスは package-private にする。
  Runner と Aspect の振る舞いは [バッチ](../batch/index.md) に従う。
- メールアドレスの形式の制約 `MailAddress` を `shared.validation` に置き、このパッケージも `@NamedInterface("validation")` で公開する（[メールアドレスの扱い](../mail/email-address.md#形式の検査)）。
- 業務の概念を置かない。
  ルートのパッケージには `package-info.java` だけを置く。
- `shared.persistence` を使ってよいのは、他のモジュールの `infrastructure.persistence` だけである。
  `shared.eventresubmission` は `shared.persistence` と同じ層として扱う。`shared` に `infrastructure` のパッケージは置かない。
  `shared.batch` を使ってよいのは、機能モジュールの `batch` だけである。
  `shared.concurrency`、`shared.failure`、`shared.events`、`shared.security`、`shared.tenancy`、`shared.validation` は、どの層からも使える。

使う場所の制限は、`PackageByFeatureOnionArchitectureTest` の `sharedModuleIsUsedOnlyByPersistenceAdapters` が検査する。
ただし、`shared` の `PgmCdAspect` は、AOP で `<UseCase>CommandHandler` の `handle` と `<Event>Listener` の呼び出しを囲む（[ADR-051](../adr/ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
`shared.tenancy` の `TenantListenerAspect` も、`@ApplicationModuleListener` の呼び出しを囲み、イベントのテナントを束縛する（[ADR-105](../adr/ADR-105-bind-tenant-to-request-and-event-context.md)）。
この呼び出しはクラスの依存にならないため、ArchUnit では検査できない。
`shared.batch` を使える層の制限と、`TenantPartitionAspect` を `TenantContext.call` の許可先に足すことは、まだ検査していない（[#667](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/667)、[バックエンドのアーキテクチャテスト](architecture-tests.md)）。

`shared` は機能モジュールではないため、[モジュール間の連携](architecture.md#モジュール間の連携)の対象にならない。
機能モジュールの間の連携は、`shared` があってもイベントと `<Feature>Queries` だけにする。

## 関連資料

- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](../adr/ADR-048-add-shared-module-for-jooq-common-code.md)
- [バックエンドアーキテクチャ](architecture.md)
- [バックエンドのアーキテクチャテスト](architecture-tests.md)
