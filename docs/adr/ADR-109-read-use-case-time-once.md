---
type: ADR
title: 'ADR-109: ユースケースの現在時刻を 1 回だけ読み、PgmCdAspect が ScopedValue で渡す'
description: 1 つのユースケース（CommandHandler の handle、Listener の 1 回の配信）の中で現在時刻を 1 回だけ読み、業務の時刻と共通カラムの *_at に同じ値を使う決定。PgmCdAspect が一番外の呼び出しで Clock を読んで ScopedValue に束縛し、Listener の中の CommandHandler は Listener の値を引き継ぐ。決済の recordedAt は処理の開始時刻になる。
tags: [adr, datetime, backend, database, audit-columns, aspect]
---

# ADR-109: ユースケースの現在時刻を 1 回だけ読み、PgmCdAspect が ScopedValue で渡す

## Status

Proposed

## Date

2026-11-21

## Context

[ADR-006](ADR-006-utc-instant-absolute-time-policy.md) は、現在時刻を `Clock` のコンストラクタ注入と `now(Clock)` で取ると決めている。
この決定は、1 つの処理の中で時計を何回読むかを決めていない。

実装では、時計を読む場所が分かれていた。
`CommonColumns` は、`forInsert` と `forUpdate` の呼び出しのたびに `Instant.now(clock)` を読む。
注文の下書きでは、`ordered_at` を CommandHandler が読み、`t_order` の `created_at` と明細の行ごとの `created_at` を `CommonColumns` が別に読む。
確定では、`OrderConfirmed` の時刻と `t_order.updated_at` を別に読む。
システムの `Clock` はマイクロ秒の tick なので、2 回読むと値はほぼ必ず違う（JDK 25.0.5 の jshell で 1000 回のうち 993 回）。

テストは `Clock.fixed(...)` を渡すため、何度読んでも同じ値になり、この食い違いが見えない。
issue #322 で調べた別のリポジトリ（`yokozi-jp/agent-software-factory` の ADR 0043）でも、トランザクションを開くたびに時計を読み直し、1 つのメッセージの処理で時刻が最大 3 つになっていた。

Spring にも PostgreSQL にも、1 つのユースケースの現在時刻を `Clock` から 1 回だけ読む仕組みはない。
`transaction_timestamp()` は `Clock.fixed` で固定できず、トランザクションを 2 つ開く処理でそろわない（[PostgreSQL の共通カラム](../database/postgresql-common-columns.md)の「既定値を持たない理由」）。

一方、[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md) の `PgmCdAspect` は、すでに CommandHandler の `handle` と Listener の public メソッドを囲み、`*_pgm_cd` を `ScopedValue` に束縛している。
ユースケースの入口は、この Aspect が囲む呼び出しと一致する。

## Decision

1 つのユースケース（HTTP の要求で呼ぶ CommandHandler の `handle`、Listener の 1 回の配信）の中で、現在時刻を 1 回だけ読む。
業務の時刻（`ordered_at`、イベントの時刻、決済の `recorded_at`）と共通カラムの `*_at` には、その同じ値を使う。
ADR-006 の「現在時刻は `Clock` をコンストラクタ注入し、`now(Clock)` で取得する」に、この規則を足す。

- `shared.time` に `UseCaseTime` を置き、現在時刻を `ScopedValue<Instant>` に束縛する。
  `UseCaseTime.call(Clock, ...)` は、束縛がなければ時計を 1 回読んで束縛し、束縛があれば時計を読まずに引き継ぐ。
  `UseCaseTime.now()` は束縛された値を返し、束縛がなければ `IllegalStateException` を投げる。
- `PgmCdAspect` は `Clock` をコンストラクタで受け取り、`*_pgm_cd` の束縛の外側で `UseCaseTime.call` を呼ぶ。
  一番外の CommandHandler か Listener の呼び出しが時刻を決める。
- Listener の中で呼ぶ CommandHandler は、Listener の時刻を引き継ぐ。
  Listener の 1 回の配信を 1 つのユースケースとして扱い、Listener と CommandHandler が書く行とイベントの時刻をそろえるためである。
- CommandHandler は `Clock` を受け取らず、`UseCaseTime.now()` で読む。
  ArchUnit の `commandHandlersAndListenersDoNotReadClock` で、`application` の CommandHandler と Listener の `Clock` への依存を検査する。
- `CommonColumns` は `Clock` を持たず、`UseCaseTime.now()` を `*_at` に登録する。
  束縛がないときに時計を読み直す代替の経路は作らない。
  代替の経路があると、束縛し忘れた呼び出しが例外にならず、時刻の食い違いが戻るからである。
- 決済の `recordedAt` は、決済代行の応答の後に読んだ時刻ではなく、決済を記録した処理の開始時刻（Listener の配信の時刻）にする。
  `t_payment.created_at` と同じ値になる。
  OpenAPI の `recordedAt` の説明も「請求の結果を記録した処理の開始時刻」に改める。
- 失敗したイベント出版の再投入（`EventPublicationResubmitter` と `EventPublicationResubmitOnce`）の時計の読み取り（時間の予算の判定、出版の年齢、ShedLock のロックの開始時刻）は対象外とし、`Instant.now(clock)` で読み続ける。
  これらは共通カラムを書かず、時間の予算の判定は処理の途中で経過時間を測る必要があるためである。
- `@Scheduled` の 1 回の実行も 1 つのユースケースとして扱う。
  現在、`CommonColumns` で書く `@Scheduled` の処理はない（失敗したイベント出版の再投入は Listener を呼び、Listener の呼び出しで束縛される）。
  `@Scheduled` の処理が `CommonColumns` で書くときは、その入口で `UseCaseTime.call` を呼ぶ。
- 時刻を 1 回だけ読むことを確かめるテストには、読むたびに進む時計（テストの `AdvancingClock`）を渡す。

## Consequences

### Positive

- 1 つのユースケースで書く行とイベントの時刻が同じ値になり、`ordered_at` と `created_at` のような業務の時刻と共通カラムを突き合わせて調べられる。
- CommandHandler のコンストラクタから `Clock` が消え、時刻の取り方が `PgmCdAspect` の一か所にまとまる。
- 束縛し忘れた書き込みは例外になり、テストで気付ける。
- 読むたびに進む時計のテストが、時計を 2 回読む誤りを検出する（`CommonColumns` が自分で時計を読む形に戻すと、`OrderUseCaseTimeTest` が失敗する）。

### Negative

- 時刻への依存が型に出ない。
  CommandHandler の引数と Command からは、現在時刻を使うことが読み取れない。
- Spring AOP のプロキシを通らない呼び出しでは束縛されないため、CommandHandler を直接 `new` して呼ぶテストは `UseCaseTime` を束縛する必要がある。
- `recordedAt` は、決済代行の応答の時刻より前になる。
  決済代行の応答が遅いときは、その分だけ実際に記録した時刻と差が出る。

### Neutral

- `PgmCdAspect` の責務が、`*_pgm_cd` に加えてユースケースの現在時刻の束縛に広がる。
- バッチのように CommandHandler と Listener を通らない書き込みの入口を作るときは、ADR-051 と同じく、入口で `*_pgm_cd` と現在時刻を束縛する方法を決める。
- 画面の「結果を記録した時刻」の表示の文言は、この ADR では変えない。

## Alternatives Considered

### 選択肢1: 入口が読んだ now を Command か引数で渡す

- **Description**：`agent-software-factory` の ADR 0043 と同じく、入口が読んだ `now` を Command か引数で CommandHandler に渡し、`CommonColumns` も `now` を引数で受け取る。
- **Pros**：時刻への依存が型に出る。Aspect に頼らず、呼び出しの経路をコードから読める。
- **Cons**：Repository の `add(集約)` と `update(集約)` は時刻を知らないため、ADR-051 の選択肢1と同じく、Domain のインタフェースか集約に共通カラムの時刻を持ち込むことになる。すべての Command、Repository、`TableWriter` の呼び出しの形が変わる。

### 選択肢2: Listener の中の CommandHandler が時刻を読み直す

- **Description**：束縛を入れ子の呼び出しごとに上書きし、`*_pgm_cd` と同じく内側の CommandHandler が自分の時刻を読む。
- **Pros**：`*_pgm_cd` と束縛の規則が同じになる。
- **Cons**：Listener の 1 回の配信で、Listener と CommandHandler の時刻が分かれる。いまの Listener は CommandHandler を 1 つ呼ぶだけで書かないため差は出ないが、Listener が書く行やイベントを足したときに食い違いが戻る。

### 選択肢3: トランザクションの開始時刻（transaction_timestamp()）を使う

- **Description**：PostgreSQL の `transaction_timestamp()` を `*_at` と業務の時刻に使う。
- **Pros**：アプリケーションの仕組みが要らない。
- **Cons**：`Clock.fixed` で固定できず、テストで時刻を決められない。1 つのユースケースでトランザクションを 2 つ開くとそろわない。イベントの時刻のように DB に書かない値には使えない。

## References

- [ADR-006: 絶対時刻を UTC / Instant / timestamptz に統一する](ADR-006-utc-instant-absolute-time-policy.md)
- [ADR-051: 共通カラムの pgm_cd を Aspect と ScopedValue で渡し、Aspect をトランザクションの外側で動かす](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)
- [日時とタイムゾーンの規約](../datetime/timezone-conventions.md)
- [PostgreSQL の共通カラム](../database/postgresql-common-columns.md)
- [issue #584](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/584)
- [issue #322](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/322)
- [JEP 506: Scoped Values](https://openjdk.org/jeps/506)
