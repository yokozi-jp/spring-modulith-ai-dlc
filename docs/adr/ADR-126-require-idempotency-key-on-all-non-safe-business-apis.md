---
type: ADR
title: 'ADR-126: GET と HEAD 以外のすべての業務の API に Idempotency-Key を必須にし、モジュールごとの表に Result を保存する'
description: 成功して応答を失った更新の再送が 409 になる問題を避けるため、テナントを選んだ後の業務の API のうち GET と HEAD 以外のすべての操作で Idempotency-Key を必須にし、キーと CommandHandler の Result をモジュールのスキーマの表へ業務の更新と同じトランザクションで保存する決定。ADR-013 の「冪等性」の節と Alternative 3 を改める。
tags: [adr, backend, api, idempotency, database]
---

# ADR-126: GET と HEAD 以外のすべての業務の API に Idempotency-Key を必須にし、モジュールごとの表に Result を保存する

## Status

Proposed

## Date

2026-11-24

## Context

[ADR-013](ADR-013-standardize-http-api-contracts.md) は、`Idempotency-Key` を回復困難な副作用の操作だけに要求すると決め、すべての POST に要求する案（Alternative 3）を退けた。
この前提は、今は成り立たない。

- こちらの更新の操作は、成功して応答を失った後に再送すると、`lock_no` が進んでいるので 409 になる（注文の確定、取消、明細の変更）。
  注文の作成も、客先注文番号の一意索引で 409 になる。
  重複は起きないが、成功した操作が失敗に見えるので、安全に再実行できる操作とは言えない。
- 「回復困難な副作用の操作だけ」は、操作ごとの判断になり、機械で検査できない。
  一律にすれば、OpenAPI の Spectral の規則で付け忘れを止められる（[ADR-071](ADR-071-reinforce-learnings-into-five-layers.md) の、上の層を優先する方針）。
- ADR-013 は IETF の文書を失効した Internet-Draft としていた。
  今は draft-ietf-httpapi-idempotency-key-header-08（2026-10-22 公開、2027-04-25 まで有効）が出ている。
  draft 2.5.2 は、キーを要求する操作の仕様を resource が公開することを求めており、一律のほうが公開する内容が単純になる。

向こうの `yokozi-jp/agent-software-factory` は、ADR 0040 で同じ問題を、キーの一律の必須化で解いている。
調べた事実と出典は [#310](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/310) にある。
こちらは更新を PUT で行い、DELETE も `lock_no` を運ぶので、向こうが PATCH に限った対象を広げる。
キーの表は、テナントとモジュールのロールを分けた構成（#363）の上に作る。

## Decision

### 対象

テナントを選んだ後の業務の API のうち、GET と HEAD 以外のすべての操作（POST、PUT、PATCH、DELETE）で `Idempotency-Key` を必須にする。

次の経路は対象にしない。

- Spring Security のログインとログアウトなど、OpenAPI の契約の外にある経路。
- テナントを選ぶ前の操作（テナントの選択）。
  キーの主キーが `tenant_public_id` から始まり、テナントが決まる前は表に書けないためである。
  テナントの選択は、同じ値で繰り返しても、選択済みの状態が変わらないので、再送で結果が変わらない。
- 非同期のリスナーなど、HTTP の要求でない書き手。
  今の配信保証と冪等性の規約（`docs/integration/async-ordering-and-idempotency.md`）のままにする。

### ヘッダー

- 値は Structured Field の String（引用符付き）で、中身は UUID に限る（draft 2.1、2.2）。
  例：`Idempotency-Key: "<UUID>"`。
  引用符のない値と UUID でない値は、形式の誤りとして 400 にする。
- キーがないときも 400 にする（draft 2.7）。
- キーはクライアントが利用者の操作ごとに採番する。キーを払い出す API を作らない。
- 利用者がエラーの通知を見て押し直したときは、新しい操作として新しいキーを採る。
  同じキーで送るのは、クライアントの自動の再試行だけにする。
  利用者が結果を知らずに押し直すと、成功済みの操作に対する誤った 409 が残る。
  これは受け入れる（Consequences の Negative）。

### 保存先

- 書き込みの API を持つモジュールのスキーマに、キーの表を 1 つずつ置く。
  共有のスキーマに置くと、すべてのモジュールのロールに別のスキーマの権限を与えることになるためである。
- 主キーは `(tenant_public_id, 利用者の ID, キー)` にする。
  利用者の ID は、[ADR-096](ADR-096-require-app-user-principal-for-audit-columns.md) の `AppUserPrincipal` の値を使う。
- 列は、ペイロードのハッシュ、保存した Result、期限を持つ。
- RLS（`ENABLE` と `FORCE`）を付け、自分のモジュールのロールだけに権限を与える。
- 業務の更新と同じトランザクションに書くので、業務の結果とキーの記録は、一緒に commit されるか一緒に rollback される。
  Servlet の Filter や `HandlerInterceptor` など、トランザクションの外の層には置かない。

### 処理の流れ

`shared` の共通の処理を、CommandHandler のトランザクションの中で呼ぶ。

1. キーを `INSERT ... ON CONFLICT DO NOTHING` する。
2. 入ったときだけ業務を実行し、Result を保存する。
3. 行が既にあれば、ペイロードのハッシュが同じなら保存した Result を返す。違えば 400 にする。

- 同じキーの 2 つ目の要求は、キーの `INSERT` の一意索引で 1 つ目の終わりを待つ。
  1 つ目が commit すれば 0 行になって保存した Result を読み、rollback すれば自分で入る。
- ペイロードのハッシュは、操作の名前と Command から作る。
  トランザクションの中に届くのは本文のバイト列でなく Command（パスの値を含む）なので、draft 2.4 が認める「選んだ要素のチェックサム」にあたる。
- 保存するのは HTTP の応答でなく CommandHandler の Result にする。
  HTTP の状態コードとヘッダーは Controller が Result から作る（[ADR-050](ADR-050-define-backend-class-roles-and-naming.md)）。
  注文の作成は本文のない 201 で、`Location` に意味がある。
  Result を保存すれば、Controller が 1 回目と同じ 201 と `Location` を作り直せる。
  返し直しを示すヘッダーは付けない。
- 2xx 以外は rollback し、キーの行を残さない。
  業務の失敗（422 など）を直して同じキーで送ると、もう一度処理される。
- キーの `INSERT` のロック待ちが [ADR-055](ADR-055-set-db-time-limits-per-connection.md) の `lock_timeout` で切れたときだけ、処理中の 409 と `Retry-After` を返す。
  業務の更新のロック待ちは、今の `ConflictException`（`lock_no` の競合）のままにする。
- 外への副作用は今のまま、イベントの出版の表を通す。
  キーは exactly once を保証しないので、一意索引、`lock_no`、決済代行への注文 ID のキーは残す（ADR-013 の規約のまま）。

### 保存期間と掃除

- 保存期間は 24 時間とし、docs と OpenAPI に公開する（draft 2.3、2.5.2）。
- 期限切れの行は、[ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md) と同じ ShedLock の形の定期のジョブで、テナントごとに消す。
  ジョブはテナントとモジュールのロールを設定して動かす。
- 期限を過ぎて、まだ消えていない行は無いものとして扱い、同じキーは新しいキーとして受ける。

### 失敗の表現

次の problem type を `/problems/...` に足す（[ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)）。
処理中の 409 は、`lock_no` の競合の `ConflictException` と別の `type` にする。

- キーがない：400。
- キーの形式の誤り：400。
- ペイロードの不一致：400。
- 同じキーの処理中：409。`Retry-After` を付ける。

ペイロードの不一致は、draft 2.7 が SHOULD とする 422 でなく 400 にする。
こちらの 422 は、[ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md) で業務規則の違反に使っている。
同じ状態コードに、入力の誤りと業務の失敗が混ざると、クライアントが `type` を見ずに状態コードで分岐できなくなるためである。

### OpenAPI と検査

- OpenAPI の共通の components に、必須のヘッダーのパラメータと、上の 4 つのエラー応答を置く。
- Spectral に、GET と HEAD 以外の業務の operation が `Idempotency-Key` の必須のヘッダーを参照する規則を足し、違反の例を fixture に足す。

### クライアント

- フロントエンドの mutation は、利用者の操作ごとにキーを採番して送る。
- ネットワークの失敗、5xx、408、429、処理中の 409 では、同じキーで自動で再試行する。
  RFC 9110 9.2.2 は、冪等と分かっている要求だけを自動で再試行してよいとしており、キーがその根拠になる。
- 処理中の 409 の通知を `failureNotice` に足す。

### ADR-013 の変更

- ADR-013 の「冪等性」の節と Alternative 3 を、この ADR が改める。
  ADR-013 の Status に `Amended by` を書き、索引に表示する。
- ADR-013 の Decision は書き換えない（[ADR の運用ルール](conventions.md)）。
  Idempotency-Key の文書を「失効した Internet-Draft」とする ADR-013 の記述は、この ADR の Context が示すとおり、今は成り立たない。

## Consequences

### Positive

- 成功して応答を失った更新を再送しても、409 にならず、1 回目と同じ応答が返る。
- 付け忘れを Spectral が止める。操作ごとに、キーの要否を判断しなくてよい。
- キーの記録は業務の更新と同じトランザクションにあるので、記録だけが残る、業務だけが残る、という状態が起きない。
- キーの表も RLS とモジュールのロールの対象なので、別のテナントと別の利用者の記録に当たらない。

### Negative

- 書き込みの API を持つモジュールごとに、表、権限、RLS、掃除のジョブが増える。
- 業務の操作ごとに、キーの `INSERT` と Result の保存の分だけ書き込みが増える。
- 処理中の 2 つ目の要求は、`lock_timeout` の間、接続を 1 本使って待つ。
- 利用者がエラーの通知を見て押し直すと、成功済みの操作に対する誤った 409 が残る。
- キーが効くのは、同じ利用者、同じテナント、同じキーで、24 時間のうちに届いた再送だけである。
- draft 08 は RFC ではない。

### Neutral

- RFC になった時点で、構文と、ペイロードの不一致の状態コード（422）を見直す。
- テナントを選ぶ前の操作に冪等性が要るようになったら、別の判断をする。
- Orval が必須のヘッダーのパラメータを mutation の変数に入れるか、キーの `INSERT` の `55P03` を業務の更新のロック待ちと区別して処理中の 409 にできるかは、実装の Issue で確かめる。

## Alternatives Considered

### 選択肢1: ADR-013 のまま、回復困難な副作用の操作だけに要求する

- **Description**：決済、外部送信、重複作成の操作だけにキーを要求する。
- **Pros**：保存と掃除の実装が少ない。
- **Cons**：要否が操作ごとの判断になり、機械で検査できない。成功して応答を失った更新の再送が 409 になる問題が、対象外の操作に残る。

### 選択肢2: キーを共有のスキーマの 1 つの表に置く

- **Description**：すべてのモジュールのキーを `shared` の表に置く。
- **Pros**：表と掃除のジョブが 1 つで済む。
- **Cons**：業務の更新と同じトランザクションで書くには、すべてのモジュールのロールに共有のスキーマの権限を与える必要があり、モジュールのロールを分けた意味が薄れる。

### 選択肢3: HTTP の応答を保存し、Filter で返す

- **Description**：状態コードと本文を保存し、トランザクションの外の Filter で返し直す。
- **Pros**：業務のコードがキーを意識しない。
- **Cons**：業務の結果とキーの記録が別のトランザクションになる。ヘッダーを保存しないと `Location` が消え、保存すると古い契約の形がデプロイをまたいで残る。

### 選択肢4: ペイロードの不一致に 422 を返す

- **Description**：draft 2.7 の SHOULD に従う。
- **Pros**：draft と同じになる。
- **Cons**：ADR-062 が 422 を業務規則の違反に使っており、状態コードの意味が混ざる。

## References

- [draft-ietf-httpapi-idempotency-key-header-08](https://www.ietf.org/archive/id/draft-ietf-httpapi-idempotency-key-header-08.txt)（2.1、2.2、2.3、2.4、2.5.2、2.6、2.7）
- [RFC 9110 9.2.2 Idempotent Methods](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2)
- [PostgreSQL: Index Uniqueness Checks](https://www.postgresql.org/docs/current/index-unique-checks.html)
- [ADR-013](ADR-013-standardize-http-api-contracts.md)
- [ADR-050](ADR-050-define-backend-class-roles-and-naming.md)
- [ADR-055](ADR-055-set-db-time-limits-per-connection.md)
- [ADR-058](ADR-058-use-path-absolute-relative-uri-for-problem-types.md)
- [ADR-062](ADR-062-map-business-exceptions-to-404-409-422.md)
- [ADR-079](ADR-079-resubmit-failed-event-publications-periodically-with-shedlock.md)
- [ADR-096](ADR-096-require-app-user-principal-for-audit-columns.md)
- [#310](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/310)、[#540](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/540)
