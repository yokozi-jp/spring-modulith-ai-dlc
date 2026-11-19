---
type: ADR
title: 'ADR-104: 操作しているテナントを ScopedValue で束縛し、リクエストはセッションから、リスナーはイベントから取る'
description: 操作しているテナントの public_id を shared.tenant の ScopedValue に束縛する決定。リクエストの間は入口の filter がセッションで選んだテナントを、リスナーの間は Aspect がイベントの TenantScoped からテナントを、トランザクションより外側で束縛する。テナントを持つイベントは TenantScoped を実装し、ArchUnit で守る。
tags: [adr, backend, tenancy, aop, scoped-value, event]
---

# ADR-104: 操作しているテナントを ScopedValue で束縛し、リクエストはセッションから、リスナーはイベントから取る

## Status

Proposed

## Date

2026-11-19

## Context

[issue #369](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369) は、業務のデータをテナントごとに分け、DB の RLS でテナントの境界を守る。
RLS のポリシーは、トランザクションに設定したテナントの値と行の `tenant_public_id` を比べる（#377）。
設定する値は、トランザクションが始まる時点で、呼び出しの文脈から読めなければならない。

呼び出しの入口は 2 つある。
1 つは HTTP のリクエストで、選んだテナントはセッションの権限 `TenantMembershipAuthority` が持つ（[ADR-101](ADR-101-keep-tenants-and-roles-in-app-database.md)）。
もう 1 つは `@ApplicationModuleListener` で、発行側のトランザクションがコミットした後に、別のスレッドの新しいトランザクションで動く。
別のスレッドにはセッションも認証もない。
テナントはイベントが運ばなければ、リスナーの時点で失われる。

共通カラムの `*_pgm_cd` は、すでに同じ形で渡している。
`PgmCdAspect` が `ScopedValue` に値を束縛し、すべての `@Aspect` はトランザクションの advisor より外側で動く（[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
テナントの値の渡し方も、この形に揃えられるかを決める。

## Decision

操作しているテナントの `public_id` を、`shared.tenant` の `ScopedValue` に束縛する。

- `shared.tenant` を新しく置き、`@NamedInterface("tenant")` で公開する。どの層からも使える。
  置く型は、`public_id` の UUID を包む値オブジェクト `TenantId`、テナントを運ぶイベントの型 `TenantScoped`、束縛と読み出しの入口 `TenantContext`、リスナーの Aspect `TenantListenerAspect` である。
- `TenantContext.current()` は、束縛された `TenantId` を返す。束縛がなければ `IllegalStateException` を投げ、テナントがない状態を「データがない」と取り違えさせない。
- リクエストでは、`tenancy` の `TenantSelectionFilter` が、所属を引き直して残っているテナントを、リクエストの残りの処理の間だけ束縛する。
  テナントは認証の権限だけから取る。リクエストの header、パス、本文から取らない。
- リスナーでは、`TenantListenerAspect` が引数のイベントの `TenantScoped.tenantId()` を、呼び出しの間だけ束縛する。
  Aspect は `@Order(Ordered.LOWEST_PRECEDENCE - 200)` を付け、`PgmCdAspect` とトランザクションの advisor より外側で動かす。
  リスナーのトランザクションが始まる時点で、テナントは束縛済みになる。
  引数が `TenantScoped` でなければ、`IllegalStateException` で拒否する。
- テナントを持つイベントは、`TenantScoped` を実装し、`TenantId` の項目を持つ。イベントを発行する CommandHandler は `TenantContext.current()` を詰める。
  `@ApplicationModuleListener` の引数のイベントが `TenantScoped` を実装することを、ArchUnit の `moduleListenerEventsAreTenantScoped` で検査する。
- ArchUnit の `moduleApiDoesNotExposeInternalTypes` は、モジュールルートの型が依存してよい型に、`shared.tenant` の型を足す。イベントが `TenantId` と `TenantScoped` を使うためである。

束縛はこの決定の範囲では DB に使わない。
DB のロールとテナントの設定は #377 で、トランザクションマネージャーが `TenantContext` を読んで行う。

## Consequences

### Positive

- リスナーのトランザクションの開始の時点で、イベントのテナントが読める。#377 のトランザクションマネージャーは、リクエストとリスナーを区別せずに同じ入口を読める。
- `ScopedValue` は束縛の範囲を出ると戻るため、スレッドとプールの接続にテナントが残らない。
- テナントを持たないイベントのリスナーは、ArchUnit が先に止める。設定の漏れが実行時の別のテナントの操作になる前に気付ける。
- `*_pgm_cd` と同じ仕組みなので、読む人が覚える仕組みが増えない。

### Negative

- テナントを持つイベントは、`TenantId` の項目と `TenantScoped` の実装が必要になる。イベントの JSON が、テナントの項目の分だけ大きくなる。
- モジュールルートの型が、標準型と同じルートの型のほかに、`shared.tenant` の型へ依存する。依存を許す例外が 1 つ増える。
- リクエストでもリスナーでもない入口（バッチ、起動時の処理）は、テナントを自分で束縛する必要がある。
- Spring AOP のプロキシを通らない呼び出しと、別のスレッドへ渡した処理では束縛されない。

### Neutral

- 発行済みで未完了のイベントの出版は、テナントの項目を持たず、再投入できない。本番にデータはまだないため、移行の手順は作らない。
- `TenantListenerAspect` の `@Order` の値は `PgmCdAspect` と同じ向きで選び、ほかの Aspect を足すときに前後を決め直す。

## Alternatives Considered

### 選択肢1: ThreadLocal に設定する

- **Description**：filter とリスナーの入口で `ThreadLocal` にテナントを設定し、終わりで消す。
- **Pros**：Java のバージョンに依存しない。
- **Cons**：消し忘れると、スレッドプールで次の処理にテナントが残り、別のテナントの操作になる。[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md) と同じ理由で選ばない。

### 選択肢2: リスナーの引数でテナントを渡す

- **Description**：リスナーが `on(event, tenantId)` のように、テナントを引数で受け取り、CommandHandler の Command に詰める。
- **Pros**：束縛の仕組みが要らず、流れがコードに現れる。
- **Cons**：すべての Command と Repository の入口にテナントの引数が増え、渡し忘れた 1 か所が別のテナントの操作になる。トランザクションの開始の時点で読む入口にもならない。

### 選択肢3: リスナーの中で CommandHandler が束縛する

- **Description**：リスナーの `on` や CommandHandler が、イベントのテナントを自分で束縛する。
- **Pros**：Aspect が要らない。
- **Cons**：束縛が `@Transactional` の内側になり、トランザクションが始まる時点で値を読めない。リスナーごとに書き忘れる余地も残る。

## References

- [ADR-051: 共通カラムの pgm_cd を Aspect と ScopedValue で渡し、Aspect をトランザクションの外側で動かす](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)
- [ADR-101: テナント、所属、役割をアプリの DB に持ち、テナントを選んだ時点で役割の権限を組み立てる](ADR-101-keep-tenants-and-roles-in-app-database.md)
- [issue #369: テナントでデータを分ける](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/369)
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
