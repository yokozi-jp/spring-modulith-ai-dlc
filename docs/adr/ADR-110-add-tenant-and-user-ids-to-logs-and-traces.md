---
type: ADR
title: 'ADR-110: ログとトレースに、テナントの public_id とアプリの利用者の ID を付ける'
description: 認証した要求とリスナーの中のログと、HTTP の受信の span に、テナントの public_id を tenant.id、アプリの利用者の ID を user.id として付け、ADR-015 が発生源で渡さないとした「利用者へ結び付く ID」から、アプリが採番したこの 2 つを外す決定。ログには MDC で入れ、span には Observation の高い濃度の key value だけで入れ、baggage は使わない。
tags: [adr, observability, logging, tracing, tenancy]
---

# ADR-110: ログとトレースに、テナントの public_id とアプリの利用者の ID を付ける

## Status

Proposed

## Date

2026-11-22

## Context

[issue #378](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/378) は、障害を調べる人が、ログとトレースに出たテナントの ID をそのまま DB の検索に使えるようにする。
業務テーブルは、テナントを `tenant_public_id` に持つ（[ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)）。
ログとトレースに行の識別子を載せるときは、主キーではなく `public_id` を使う（[ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)）。

[ADR-015](ADR-015-structure-and-protect-observability-data.md) は、発生源で渡さない値に「利用者へ結び付く ID」を挙げている。
この書き方は、アプリが採番した利用者の ID も禁じるように読め、HTTP アクセスログに `user.id` を記録するとした規約と食い違っていた。
OWASP Logging Cheat Sheet は、記録する事象の属性の「Who」に、認証済みの利用者の識別子を挙げる。
一方、IdP の `sub`、login ID、メールアドレス、認証 session ID は、アプリの外のアカウントやセッションへの手がかりになる。

テナントは、リクエストでは `TenantSelectionFilter` が、リスナーでは `TenantListenerAspect` が、`TenantContext.call` で束縛する（[ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)）。
要求のスレッドの MDC は、`@Async` のリスナーへ伝わらない。
`spring.task.execution.propagate-context` が伝えるのは、登録した `ThreadLocalAccessor` だけだからである。
リスナーの中の書き込みは、伝わった利用者を使わず処理の名前で記録する（[ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)）。
再投入は認証のないスレッドから呼ばれ、利用者がいないためである。

トレースは Micrometer の Observation で作る。
Observation の低い濃度の key value はメトリクスの tag にもなり、高い濃度の key value は span の属性だけになる。
Micrometer Tracing の baggage を W3C の形式で伝えると、外向きの HTTP の要求の header にも載る。

ADR-015 の Alternative 4 は、アプリケーションの中で属性を絞らない理由の一つに、appender の属性 allowlist が使っている版に存在しないことを挙げた。
OpenTelemetry Logback appender の 2.33.0-alpha は `mdcAttributesIncluded` と `keyValuePairAttributesIncluded` を持つので、この理由は成り立たなくなった。

## Decision

### 項目名と値

- テナントは `tenant.id` にし、値をテナントの `public_id`（`TenantId.toString()` の UUID の文字列）にする。`tenancy.m_tenant` の主キーの `tenant_id`（bigint）は使わない。
- 利用者は `user.id` にし、値をアプリの利用者の ID（`AppUserPrincipal.userId()`、`tenancy.m_user` の `public_id` の UUID）にする。OpenTelemetry の semantic conventions と ECS の `user.id` に合わせる。

ADR-015 の「利用者へ結び付く ID」のうち、アプリが採番したこの 2 つだけを、ログの属性と span の属性に許す。
IdP の `sub`、login ID、メールアドレス、認証 session ID は、ADR-015 のとおり渡さない。

### ログ

- 2 つを SLF4J の MDC に入れ、ログを書く処理から key-value で渡さない。
- `tenant.id` は、`TenantContext.call` が束縛の間だけ入れる。入れ子の束縛から戻ると外側のテナントに戻し、束縛を出ると消す。
- `user.id` は、`TenantSelectionFilter` が、アプリの利用者（`AppUserPrincipal`）として認証したリクエストの所属の引き直しを含む残りの処理の間だけ入れる。リスナーには入れない。
- OpenTelemetry appender は `mdcAttributesIncluded` で MDC のうち 2 つだけを属性にし、Collector の `keep_keys` に 2 つを足す。
- コンソールの ECS JSON は、Spring Boot が MDC を出すので、設定を足さない。

### トレース

- `TenantSelectionFilter` が、HTTP の受信の observation の高い濃度の key value に 2 つを足し、span の属性にする。低い濃度の key value には入れない。
- Micrometer Tracing の baggage（`management.tracing.baggage.*`）は使わない。
- リスナーの処理の span は Spring Modulith が作る（`module.invocation-type=event-listener`）。この span には付けない。リスナーのログには `tenant.id` が付くので、ログの trace ID からたどる。

### ADR-015 の Alternative 4 の理由

ADR-015 の Alternative 4 の理由のうち、appender の属性 allowlist が使っている版に存在しないことは、2.33.0-alpha で成り立たなくなった。
ログの属性の allowlist を Collector に置く ADR-015 の判断は変えない。
ログ、トレース、標準出力で別々の仕組みが要るという残りの理由は、今も成り立つためである。
appender の `mdcAttributesIncluded` は、MDC のうち OTLP の属性にする項目を選ぶためだけに使う。

## Consequences

### Positive

- ログとトレースに出たテナントの ID で、そのまま業務テーブルの `tenant_public_id` を検索できる。
- HTTP の要求の中のログと受信の span から、操作した利用者とテナントを特定できる。
- ログを書く処理は、テナントと利用者を渡さずに済む。
- 束縛する唯一の処理が MDC にも入れるので、束縛したテナントとログのテナントが食い違わない。

### Negative

- ログに `tenant.id` と `user.id` が付かない範囲がある（リスナーの外のログ、`TenantSelectionFilter` の外の拒否と CSRF のログは両方、所属の引き直しの間のログは `tenant.id` だけ）。範囲は[可観測性データの規約](../observability/conventions.md#テナントと利用者)に書く。認可の拒否のログに `user.id` を付ける案は #635 で扱う。
- 受信の span 以外の span（外向きの HTTP、SQL、リスナーの span とその中の処理）は `tenant.id` を持たない。受信の span の trace ID からたどる。
- 利用者の ID から利用者を特定できるので、ADR-015 の閲覧権限と保持期間で守る。

### Neutral

- span の属性とメトリクスの tag に allowlist をかけない ADR-015 の方針は変えない。
- テナントの束縛の型（`TenantContext`）が SLF4J の MDC に依存する。

## Alternatives Considered

### Alternative 1: filter と Aspect がそれぞれ MDC に入れる

- Description：テナントを束縛する `TenantSelectionFilter` と `TenantListenerAspect` が、それぞれ MDC にも入れる。
- Pros：束縛の型がログに依存しない。
- Cons：束縛する入口が増えるたびに MDC を入れ忘れうる。入れ子の束縛から戻るときに、MDC と束縛が食い違う。

### Alternative 2: Micrometer Tracing の baggage で MDC と span に入れる

- Description：`management.tracing.baggage.correlation.fields` と `remote-fields` で、baggage の値を MDC と span に入れる。
- Pros：設定だけで MDC と span の両方に入り、`@Async` のスレッドにも伝わる。
- Cons：W3C の伝播で baggage が外向きの HTTP の header に載り、決済代行の呼び出しにテナントと利用者の ID が渡る。

### Alternative 3: ログを書く処理が key-value で渡す

- Description：ログを書く処理が `addKeyValue` で `tenant.id` と `user.id` を渡す。
- Pros：MDC を使わず、appender の設定が要らない。
- Cons：ログを書くすべての処理が渡す必要があり、渡し忘れを検出できない。Spring やライブラリが書くログには付かない。

### Alternative 4: 低い濃度の key value で span に入れる

- Description：HTTP の受信の observation の低い濃度の key value に 2 つを足す。
- Pros：メトリクスをテナントごとに集計できる。
- Cons：`http.server.requests` の系列が、テナントと利用者の数だけ増える。

## References

- [ADR-015](ADR-015-structure-and-protect-observability-data.md)
- [ADR-051](ADR-051-bind-pgm-cd-with-scoped-value-and-aspect.md)
- [ADR-089](ADR-089-keep-primary-keys-inside-modules-and-expose-public-ids.md)
- [ADR-105](ADR-105-bind-tenant-to-request-and-event-context.md)
- [ADR-107](ADR-107-isolate-tenants-with-rls-set-per-transaction.md)
- [可観測性データの規約](../observability/conventions.md)
- [OWASP Logging Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html)
- [OpenTelemetry: User attributes](https://opentelemetry.io/docs/specs/semconv/registry/attributes/user/)
- [Micrometer Observation: Introduction](https://docs.micrometer.io/micrometer/reference/observation/introduction.html)
- [OpenTelemetry Logback Appender](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/main/instrumentation/logback/logback-appender-1.0/library)
- `backend/src/main/java/com/example/demo/shared/tenant/TenantContext.java`
- `backend/src/main/java/com/example/demo/tenancy/presentation/web/TenantSelectionFilter.java`
- `backend/src/main/resources/logback-spring.xml`
- `docker/otel-collector/config.yaml`
