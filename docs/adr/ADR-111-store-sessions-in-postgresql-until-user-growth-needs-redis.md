---
type: ADR
title: 'ADR-111: ユーザー数が未知の間はセッションを PostgreSQL に保存し、Redis は負荷が出たときに戻す'
description: 利用者が少ない B2B システムの開発初期に、Spring Session の保存先を Redis から PostgreSQL へ替えて Redis を外し、利用者が増えて DB の負荷になったときに Redis へ戻す方針。
tags: [adr, session, redis, postgresql, architecture]
---

# ADR-111: ユーザー数が未知の間はセッションを PostgreSQL に保存し、Redis は負荷が出たときに戻す

## Status

Proposed

## Date

2026-11-22

## Context

[ADR-007](ADR-007-session-based-auth-with-oidc-pkce.md) は、認証状態を Spring Session + Redis のサーバ側セッションで保持すると決めた。
Redis をバックエンドが使う用途は、このセッションだけである（キャッシュ、ロック、キューには使っていない。ロックは ShedLock が PostgreSQL の `modulith.shedlock` に持つ）。

このシステムは B2B で、開発初期の利用者数は未知であり、多くないと見込む。
利用者が少ない間、Redis のためだけに次を持つ負担のほうが大きい。

- 本番、ステージング、ローカル、テストの各環境の Redis（compose、Testcontainers、CI、slot ごとのポート）。
- Redis の運用（永続化、監視、バックアップ、版の更新）。
- デプロイの前提の増加（DB に加えて Redis が起きていないとログインできない）。

一方、PostgreSQL は業務データのために必ず運用する。
[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) では、分散ロックも Redis ではなく PostgreSQL の表に置いている。
[PostgreSQL の性能対策と負荷分散](../database/postgresql-performance.md)も、Redis などのリモートキャッシュは、チューニングとリードレプリカで解決できない場合に限って導入すると定めている。

Spring Session は保存先を `SessionRepository` で抽象化しているため、保存先の差し替えは依存、設定、表の追加で済み、アプリケーションのコードは変わらない。

## Decision

開発初期は、Spring Session の保存先を PostgreSQL（Spring Session JDBC）にし、Redis をバックエンドの依存、compose、テスト、CI から外す。

- セッションの表（`spring_session`、`spring_session_attributes`）は `modulith` スキーマに置く。Liquibase の changeset で作り、アプリケーション用ロールに SELECT、INSERT、UPDATE、DELETE を与える。ShedLock の表（ADR-079）と同じ扱いである。
- アプリケーションの起動時に Spring Session が表を作らないようにする（`spring.session.jdbc.initialize-schema=never`）。表の作成は [DB マイグレーション規約](../database/migrations.md)の経路だけにする。
- セッションの直列化は、ADR-007 の実装のままにする（`OidcUser` の実装のクラス名ごとの Java 直列化）。保存先が替わっても、権限のクラスを移動、改名するときにセッションを消す規約は変わらない。
- 期限切れのセッションは、Spring Session JDBC の定期削除（`spring.session.jdbc.cleanup-cron`）で消す。

Redis へ戻す条件は、セッションの読み書きが PostgreSQL の負荷として観測され、チューニングで解決できなくなったときとする。
観測には、スロークエリ、`spring_session` へのクエリの所要時間、接続プールの待ち（Hikari の pending）を使う。
具体的なしきい値は、利用者数の実績と非機能要件が決まってから決め、この ADR では決めない。
戻すときは新しい ADR を起こし、次のとおり進める。

- `spring-boot-starter-session-data-redis` を依存に戻し、`spring.session.data.redis` を設定し、`spring_session` の表を落とす。
- アプリケーションのコードは変えない。
- 切り替えの時点で、すべての利用者のセッションが切れ、再ログインになる。

## Consequences

### Positive

- 常駐するミドルウェアが 1 つ減る。compose、Testcontainers（テストの JVM ごとに 1 つ起動していた）、CI、環境変数、slot のポートから Redis がなくなる。
- セッションのバックアップ、監視、障害対応が PostgreSQL に一本化される。
- 認証の可用性が、DB 以外のミドルウェアに依存しなくなる。

### Negative

- 認証済みのリクエストごとに、セッションの読み取りと、変更があれば書き込みが PostgreSQL に走る。利用者が増えると、業務のクエリと接続プールを競う。
- 期限切れのセッションの削除が、定期のクエリになる。Redis の TTL のような自動の失効ではない。
- 利用者が増えて Redis へ戻すときは、セッションが全部切れ、再ログインになる。

### Neutral

- セッションの表は ShedLock と同じく `modulith` スキーマに置くので、アプリケーション用ロールが読み書きし、モジュールのロールは触れない。
- Redis へ戻す判断は、数値のしきい値ではなく、観測と新しい ADR で行う。

## Alternatives Considered

### 選択肢1: Redis を使い続ける（ADR-007 のまま）

- **Description**：Spring Session + Redis を維持する。
- **Pros**：セッションの読み書きが DB の負荷にならない。TTL で自動に失効する。
- **Cons**：利用者が少ない間は、Redis の運用と環境ごとの構築が、得られる利点に見合わない。

### 選択肢2: セッションをメモリに持つ（Redis も DB も使わない）

- **Description**：Servlet コンテナのメモリにセッションを持つ。
- **Pros**：追加の依存も表もない。
- **Cons**：デプロイや再起動のたびに全員が再ログインになり、複数インスタンスでセッションを共有できない。ADR-007 が求める複数インスタンスでの共有を満たさない。

### 選択肢3: stateless な JWT をブラウザに持たせる

- **Description**：ADR-007 が却下した方式に切り替える。
- **Pros**：サーバがセッションを持たない。
- **Cons**：ADR-007 の却下の理由（XSS による窃取、失効の難しさ）がそのまま残る。

## References

- [ADR-007: セッションベース認証と OIDC Authorization Code + PKCE](ADR-007-session-based-auth-with-oidc-pkce.md)
- [ADR-079: 失敗したイベント出版を ShedLock で定期に再投入する](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-085: テストの JVM ごとに Testcontainers で依存を起動する](ADR-085-start-test-dependencies-per-fork-with-testcontainers.md)
- [PostgreSQL の性能対策と負荷分散](../database/postgresql-performance.md)
- Spring Session JDBC: <https://docs.spring.io/spring-session/reference/configuration/jdbc.html>
