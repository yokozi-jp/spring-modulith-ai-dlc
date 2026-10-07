---
type: ADR
title: 'ADR-051: 共通カラムの pgm_cd を Aspect と ScopedValue で渡す'
description: 共通カラムの *_pgm_cd の値を、shared の Aspect が CommandHandler と Listener の呼び出しの間だけ ScopedValue に束縛し、CommonColumns がそれを読む決定。spring-boot-starter-aspectj を依存に加える。
tags: [adr, backend, database, aop]
---

# ADR-051: 共通カラムの pgm_cd を Aspect と ScopedValue で渡す

## Status

Proposed

## Date

2026-10-03

## Context

[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)の `created_pgm_cd` と `updated_pgm_cd` には、行を書いたユースケースを `モジュール名.ユースケース名` の形で登録する。
共通カラムの値は、[ADR-048](ADR-048-add-shared-module-for-jooq-common-code.md) の `shared` の `CommonColumns` が組み立て、`Jooq<Aggregate>Repository` がそれを受け取って書く。

[ADR-050](ADR-050-define-backend-class-roles-and-naming.md) は、Repository のインタフェースを Domain の語彙で `domain.model` に置き、書き込みを `add(集約)` と `update(集約)` に決めている。
Repository の実装は、どのユースケースから呼ばれたかを引数で受け取れない。
ユースケースのクラスを引数で渡すと、Domain のインタフェースが Application の概念を持ち、すべての書き込みのメソッドの形が変わる。

一方、ユースケースの入口は ADR-050 で `<UseCase>CommandHandler` の `handle` と `<Event>Listener` の `on` に決まっており、名前は ArchUnit で検査している。
入口の名前から、ユースケース名を機械的に求められる。

## Decision

`org.springframework.boot:spring-boot-starter-aspectj` を依存に加え、`shared.infrastructure.persistence` に `@Aspect` の `PgmCdAspect` を置く。

- `PgmCdAspect` は、`com.example.demo.<モジュール>..` の `*CommandHandler` の `handle` と、`*Listener` の public メソッドの呼び出しを `@Around` で囲む。
- 呼び出しの間だけ、`ScopedValue<String>` に `モジュール名.クラスの単純名から CommandHandler か Listener を除いた名前`（`ordering.PlaceOrder`）を束縛する。
- 呼び出しが入れ子になったら、その間は内側の値になり、戻ると外側の値に戻る。
  ADR-050 では `<Event>Listener` の `on` が `<UseCase>CommandHandler` の `handle` を一つだけ呼ぶため、イベントを受けた書き込みの `*_pgm_cd` には、内側の CommandHandler の名前（`ordering.ChargeOrder`）が入る。
- `CommonColumns` の `forInsert(Table)` と `forUpdate(Table)` は、束縛された値を `*_pgm_cd` に登録する。
  何も束縛されていなければ `IllegalStateException` を投げ、登録を失敗させる。
- `PgmCdAspect` は、`*Listener` の呼び出しの間、内側の CommandHandler の呼び出しも含めて、Listener の中であることも `ScopedValue<Boolean>` に束縛する。
  `CommonColumns` は、Listener の中の書き込みの `*_by` に、伝わった利用者の認証を使わず `*_pgm_cd` と同じ値を登録する。
  理由は次の三つである。
  - 同じ種類の行の `*_by` が、配信の経路で意味を変えないようにする。
    通常の配信は利用者の要求のスレッドから認証を受け継ぐことがあるが、再投入は認証のないスレッドから呼ばれる。
    認証で決めると、同じ処理の行が利用者の `sub` と処理の名前に分かれる。
  - Listener のスレッドに認証が渡るかは非同期の実行の設定に依存し、その仕組みはアプリケーションのコードから読めない（[issue #122](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/122) の確認では、`spring.task.execution.propagate-context` を外しても渡った）。
    その引き継ぎに頼らない。
  - 起点の利用者は、起点の集約の行の `*_by` と、`*_tx_id` の trace からたどれる。
  起点の利用者を行に残す要件が出たら、利用者をイベントに明示的に載せ、`*_by` とは別の列に書く形で足す。

Aspect は Spring AOP のプロキシで動かし、ロード時やコンパイル時のウィービングは使わない。

## Consequences

### Positive

- Repository のインタフェースと `CommonColumns` の呼び出しに、ユースケースの引数が要らない。
- `*_pgm_cd` の値の求め方が一か所にまとまり、Repository ごとに食い違わない。
- `ScopedValue` は束縛の範囲を出ると自動で戻るため、`ThreadLocal` のような後始末の漏れが起きない。
- CommandHandler と Listener の外から共通カラムを書こうとすると、INSERT と UPDATE が失敗して気付ける。

### Negative

- AspectJ の依存（`aspectjweaver`）が増える。
- `*_pgm_cd` の値が、クラスの名前の規約（ADR-050 の役割名）に依存する。
  役割名を変えるときは、`PgmCdAspect` の pointcut も変える必要がある。
- Spring AOP のプロキシを通らない呼び出し（同じクラスの中からの呼び出し、Bean でないインスタンスの呼び出し）では束縛されない。
- バッチのように CommandHandler と Listener を通らない書き込みの入口を作るときは、入口の役割と pointcut を決め直す必要がある。

### Neutral

- `@ApplicationModuleListener` の非同期の呼び出しでも、Listener を実行するスレッドで束縛されることをテストで確かめる。

## Alternatives Considered

### 選択肢1: ユースケースのクラスを引数で渡す

- **Description**：`CommonColumns.forInsert(table, PlaceOrderCommandHandler.class)` のように、ユースケースのクラスを引数で渡す。
- **Pros**：仕組みが明示的で、依存が増えない。
- **Cons**：Repository の `add(集約)` と `update(集約)` はユースケースを知らないため、Domain のインタフェースにユースケースの引数を足すことになる。Domain が Application の概念を持つ。

### 選択肢2: 呼び出し元のスタックを StackWalker でたどる

- **Description**：`CommonColumns` が `StackWalker` で呼び出し元のスタックをたどり、最も近い `*CommandHandler` か `*Listener` のクラスを探す。
- **Pros**：依存が増えず、Repository の形も変わらない。
- **Cons**：書き込みのたびにスタックをたどる。値がどこで決まるかがコードから読み取れず、プロキシや非同期の実行でスタックの形が変わると誤る。

### 選択肢3: Aspect で ThreadLocal に設定する

- **Description**：同じ Aspect で、`ScopedValue` の代わりに `ThreadLocal` に設定する。
- **Pros**：Java のバージョンに依存しない。
- **Cons**：`finally` で消し忘れると、スレッドプールで次の処理に値が残る。入れ子の呼び出しでは外側の値を退避して戻す処理を自分で書く必要がある。`ScopedValue` は Java 25 で正式な API になり（[JEP 506](https://openjdk.org/jeps/506)）、どちらも言語の仕組みで扱える。

### 選択肢4: トランザクションの名前を使う

- **Description**：Spring がトランザクションに付ける名前（`TransactionSynchronizationManager.getCurrentTransactionName()`）から、`handle` を宣言したクラスを求める。
- **Pros**：Aspect も依存も要らない。
- **Cons**：Listener が開いたトランザクションに CommandHandler が参加すると、名前は Listener のままになり、内側のユースケースを登録できない。トランザクションの名前の形式は Spring の実装の詳細である。

## References

- [ADR-048: jOOQ の共通処理を共有モジュール shared に置く](ADR-048-add-shared-module-for-jooq-common-code.md)
- [ADR-050: バックエンドのクラスの役割と命名を定める](ADR-050-define-backend-class-roles-and-naming.md)
- [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
- [Spring Framework, Declaring an Aspect](https://docs.spring.io/spring-framework/reference/core/aop/ataspectj/at-aspectj.html)
- [Spring Framework, Proxying Mechanisms](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html)
