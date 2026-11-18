---
type: ADR
title: 'ADR-094: 共通カラムの作成者と更新者には、AppUserPrincipal の利用者の ID だけを登録する'
description: 共通カラムの *_by に、IdP の sub ではなくアプリの利用者の ID を入れることを、shared.security の AppUserPrincipal の型で守る決定。principal がこの型でない認証の書き込みは失敗させる。
tags: [adr, security, database, spring-security, spring-modulith]
---

# ADR-094: 共通カラムの作成者と更新者には、AppUserPrincipal の利用者の ID だけを登録する

## Status

Proposed

## Date

2026-11-18

## Context

Issue #369 は、共通カラムの作成者と更新者（`*_by`）に、IdP の `sub` ではなくアプリの利用者の ID を入れると決めた。
Issue #374 では、`tenancy` がログインのときに `(iss, sub)` から利用者を引き、`OidcUser#getName()` をその ID にした。
`CommonColumns` は、認証の principal が `OidcUser` なら `getName()` を登録していた。

この形では、「`getName()` がアプリの利用者の ID である」ことを何も検査しない。
次の経路では、例外にならずに `sub` が入る。

- `tenancy` の `OAuth2UserService` の Bean がない構成。Spring Security は標準の `OidcUserService` に戻り、名前が `sub` になる。
- デプロイの前に Redis に保存したセッション。principal は `DefaultOidcUser` のままで、名前が `sub` である。
- テストが使う Spring Security Test の `oidcLogin()` の既定の利用者。

`*_by` は監査のための列で、誤った値が入ると後から直せない。
`shared` は機能モジュールに依存できないため、`tenancy` の型を `CommonColumns` から参照できない。

## Decision

- 認証の principal の語彙を置く `shared.security` を作り、`AppUserPrincipal`（`userId()` を持つインターフェース）を置く。`@NamedInterface("security")` で公開し、どの層からも使えるようにする。
- `tenancy` の `AppOidcUser` に `AppUserPrincipal` を実装させる。
- `CommonColumns` は、Listener の外の認証済みの書き込みでは、principal が `AppUserPrincipal` のときだけ `userId()` を登録する。それ以外の principal では `IllegalStateException` にする。

## Consequences

### Positive

- `sub` が `*_by` に入る経路が、型で閉じる。Bean の配線の誤りや古いセッションは、誤った値ではなく書き込みの失敗として表に出る。
- #375 以降でテナントや所属を principal に持たせるときの置き場所になる。

### Negative

- 名前が `sub` の principal を保存していた版から上げるときは、デプロイと同時にセッションを消す必要がある。消さないと、そのセッションの書き込みが失敗する。手順は[APIの認証、セッション、権限](../web-api/authentication-and-session.md)にある。
- 書き込みをする MockMvc のテストは、`oidcLogin()` の既定の利用者ではなく、`testkit` の `AppUsers.oidcLogin()` を使う。

### Neutral

- `shared` に、永続化の外の語彙のパッケージが 1 つ増える。ArchUnit の `dependenciesPointInward` と `sharedModuleIsUsedOnlyByPersistenceAdapters` は、`shared.security` を `shared.concurrency` と同じ扱いにする。
- 今の利用先は `tenancy` の `presentation.web` だけだが、ほかの `shared` の語彙のパッケージにそろえて、どの層からも使えるようにする。
- `AppUserPrincipal` には、利用者の識別だけを置く。#375 で選ぶテナントは、リクエストごとに選ぶ状態なので principal に足さない。principal に足すと Redis のセッションの形が変わり、デプロイのたびにセッションを消すことになるためである。

## Alternatives Considered

### 選択肢1: `OidcUser#getName()` を登録したままにする

- **Description**：`tenancy` が名前をアプリの利用者の ID にすることに頼り、`CommonColumns` では検査しない。
- **Pros**：型を足さずに済む。
- **Cons**：Context の 3 つの経路で、例外にならずに `sub` が入る。

### 選択肢2: `CommonColumns` が `tenancy` の型を直接参照する

- **Description**：`AppOidcUser` を公開し、`CommonColumns` が `instanceof` で判定する。
- **Pros**：新しいパッケージが要らない。
- **Cons**：`shared` が機能モジュールに依存し、ADR-048 の依存の方向に反する。

## References

- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [PostgreSQLの共通カラム](../database/postgresql-common-columns.md)
- [APIの認証、セッション、権限](../web-api/authentication-and-session.md)
- #369、#374
