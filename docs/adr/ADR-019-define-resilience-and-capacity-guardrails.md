# ADR-019: 外部連携の耐障害性と容量制御を標準化する

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドは Resilience4j と仮想スレッドを導入済みだが、外部連携に適用する timeout、retry、circuit breaker の共通値を定めていない。

現時点ではアプリケーションが所有する外部 HTTP クライアントがなく、PostgreSQL、Redis、OIDC の通信は各 Spring starter が管理している。
Resilience4j の設定を追加しても、これらの通信には自動適用されない。

retry をすべての呼び出しへ一律に適用すると、非冪等な更新や認証処理を重複実行する可能性がある。
HTTP クライアントと Resilience4j と上位の呼び出し元が別々に retry すると、試行回数が乗算されて依存先の障害を増幅する。

仮想スレッドは待機中の Java スレッドのコストを下げるが、PostgreSQL の接続数は増やさない。
したがって、HikariCP の pool が DB 処理の backpressure と接続予算を担う。

JDK 25 では JEP 491 により、通常の `synchronized` メソッドとブロックで待機する仮想スレッドは carrier thread を解放できる。
一方、native frame などによる pinning と、制限のない外部資源への同時実行は別の問題として残る。

この判断は、外部 client を infrastructure adapter に置く [ADR-002](./ADR-002-package-by-feature-onion-architecture.md) と、環境差のある値を外部注入する [ADR-008](./ADR-008-single-application-yaml-external-config.md) に従う。

## Decision

外部連携ごとに名前付きの Resilience4j instance を定義し、client adapter の境界で適用する。

外部 HTTP client の開始値を次のように定める。

- connect timeout は 1 秒とする。
- 一回の呼び出し全体の timeout は 2 秒とする。
- client 自身の connect timeout と response timeout を設定し、TimeLimiter だけに中断を委ねない。
- retry の既定は一回だけとし、自動 retry を行わない。
- GET などの冪等な操作、または冪等性キーで重複を防げる操作だけ、`idempotent` 設定を継承して最大三回試行する。
- `idempotent` retry は 200 ミリ秒から倍率 2 の exponential backoff を使う。
- retry 対象は接続失敗、timeout、HTTP 429、HTTP 502、HTTP 503、HTTP 504 のような一時障害へ限定し、入力エラー、認証失敗、その他の HTTP 4xx は除外する。
- retry は client adapter の一層だけで行い、HTTP client や呼び出し元の retry と重ねない。

circuit breaker の既定値を、count based window 20 回、評価開始 10 回、failure rate 50 パーセント、open 30 秒、half-open 5 回とする。

外部連携を追加するときは、依存先の SLO と上位処理の時間予算から値を見直し、名前付き instance の設定と境界テストを同じ変更に含める。
既定値をそのまま採用した場合も、その根拠を client adapter の文書へ残す。

HikariCP の `maximum-pool-size` は、PostgreSQL がアプリケーションへ割り当てた接続数から算出して環境変数で注入する。
次の不等式を全環境で満たす。

```text
maximum-pool-size × 最大アプリケーションタスク数
+ マイグレーション接続
+ 監視と運用の接続
+ 障害対応用の予約枠
<= PostgreSQL max_connections
```

ローカル開発の開始値は 10、テストは 4 とし、接続取得の待機上限は 5 秒とする。
`minimum-idle` は明示せず、HikariCP の固定サイズ pool の既定動作を使う。
本番値は負荷試験と RDS の接続予算で決め、ローカル値を流用しない。

仮想スレッドは有効のまま維持する。
`synchronized` を pinning 回避だけを理由に `ReentrantLock` へ置き換えない。
外部 client または native library を追加したときは、JFR を有効にした負荷スモークで pinning と HikariCP の接続待ち時間を確認する。

## Consequences

### Positive

- 非冪等処理の意図しない再実行を既定で防げる。
- retry storm と長時間の連鎖待ちを抑え、障害を呼び出し元へ返す時間を予測しやすくなる。
- DB 接続数を仮想スレッド数から切り離し、PostgreSQL の容量から逆算できる。
- Java 25 で解消済みの monitor pinning を理由に、不要な lock の書き換えを行わずに済む。

### Negative

- 外部連携ごとに例外分類、冪等性、時間予算を決める作業が必要になる。
- 小さすぎる timeout は正常だが遅い依存先を失敗扱いにし、大きすぎる pool は PostgreSQL を過負荷にする。
- circuit breaker の初期値は実トラフィックを測定した後に調整が必要になる。

### Neutral

- 現在の PostgreSQL、Redis、OIDC 通信は Resilience4j の対象にせず、各 starter の timeout と再接続設定を使う。
- bulkhead と rate limiter は依存先の同時実行上限が判明した時点で、名前付き instance へ追加する。
- JFR の pinning 検証は常時有効化せず、負荷スモークと障害調査で実行する。

## Alternatives Considered

### すべての外部呼び出しを三回 retry する

- **Description**：共通の default retry を全 instance へ適用する。
- **Pros**：一時的な障害を追加設定なしで吸収できる。
- **Cons**：非冪等処理を重複実行し、複数層の retry が試行回数を乗算するため採用しない。

### Resilience4j のライブラリ既定値だけを使う

- **Description**：設定を置かず、ライブラリの既定値へ委ねる。
- **Pros**：設定ファイルが短くなる。
- **Cons**：バージョン更新で実効値を見失いやすく、プロジェクトの時間予算と障害判定を共有できないため採用しない。

### HikariCP の pool を仮想スレッド数に合わせて増やす

- **Description**：同時リクエスト数に比例して DB 接続数を増やす。
- **Pros**：アプリケーション側の DB 待ち時間を短縮できる場合がある。
- **Cons**：PostgreSQL の接続上限と CPU を先に使い切り、全リクエストの遅延を悪化させるため採用しない。

## References

- [Resilience4j documentation](https://resilience4j.readme.io/docs/getting-started-3)
- [HikariCP: About Pool Sizing](https://github.com/brettwooldridge/HikariCP/wiki/About-Pool-Sizing)
- [OpenJDK JEP 491: Synchronize Virtual Threads without Pinning](https://openjdk.org/jeps/491)
- [`backend/src/main/resources/application.yaml`](../../backend/src/main/resources/application.yaml)
