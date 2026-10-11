---
type: ADR
title: 'ADR-076: PIT を必須でない別のワークフローで変更ごとに実行し、coverage の下限に line とクラスやファイルごとの下限を加える'
description: PIT を手動実行だけから、差分のクラスに絞った PR ごとと main への push ごとの実行に改め、ADR-012 と ADR-027 の coverage の下限を全体の line とクラスやファイルごとの branch と line へ広げる決定。
tags: [adr, testing, quality, ci]
---

# ADR-076: PIT を必須でない別のワークフローで変更ごとに実行し、coverage の下限に line とクラスやファイルごとの下限を加える

## Status

Proposed

## Date

2026-10-11

## Context

[ADR-012](ADR-012-adopt-property-based-and-mutation-testing.md) は、業務ロジックがない段階の判断として、PIT を Backend CI の手動実行だけに置いた。
その後、product、ordering、payment の参照業務機能が入り、変異させる業務コードとテストができた。
手動実行だけでは誰も実行せず、テストの検出力が下がっても気づけない。
一方、PIT は全体の実行に通常のテストの数倍の時間がかかり、スコアの基準値もまだない。

coverage の下限は、バックエンドが JaCoCo の全体の branch 85%、フロントエンドが [ADR-027](ADR-027-adopt-frontend-testing-stack.md) の全体の branch 85% だけである。
全体の比率だけでは、ほかのクラスの余裕に隠れて、テストのないクラスやファイルが加わっても失敗しない。
実際、main の CI の JaCoCo の結果では、`OrderLine` の分岐が 0%、payment の `Money` の分岐が 50% のまま全体は 89.6% で通っていた。

最初の案は、差分のクラスがないときに全体を変異させた。
テストと `build.gradle` だけを変えた #204 では差分のクラスが 0 件で全体を変異させ、minion の TIMED_OUT を 13 回繰り返して 60 分の timeout で止まった。

2026-10-11 の実測は次のとおりである。

| 対象                 | 全体の branch | 全体の line | 最小の branch                           | 最小の line                         |
| -------------------- | ------------- | ----------- | --------------------------------------- | ----------------------------------- |
| バックエンド（main） | 89.6%         | 98.4%       | 66.7%（`JooqObservationConfig` の内部） | 71.4%（`GatewayPaymentCode`）       |
| フロントエンド       | 89.55%        | 97%         | 61.76%（`use-order-status-actions.ts`） | 66.66%（`orders/new.tsx` の route） |

バックエンドの最小の値は、`OrderLine` と payment の `Money` のテストを足した後の値である。
同じ日に、エラー応答を直す 2 つの PR（#192、#193）が業務コードとテストを加えている。

## Decision

PIT を Backend CI から外し、必須チェックにしない `mutation-test.yml` のワークフローで実行する。
起動の条件は Backend CI と同じパスの変更を含む PR、main への push、手動実行とする。
PR では、`origin/<base>` からの差分にある `src/main/java` のクラスとその内部クラスだけを変異させる。
差分のテスト `FooTest` があれば、同じパッケージの `Foo` も変異させる。
テストは、変異させるクラスのパッケージ以下のものだけを使う。
変異させるクラスがないときは PIT を実行せず、差分を取れないときは失敗する。
main への push と手動実行では、全体を全テストで変異させる。
差分の求め方は `task be-mutation-test` の `PIT_CHANGED_SINCE` に置き、CI はこの Task を呼ぶ。

PIT の minion は 1 つにし、timeout を既定の 1.25 倍と 4 秒から 2 倍と 30 秒に広げる。
テストが共有のテスト DB の表を消すため、minion を並列にできない。
また、新しい minion は Spring のコンテキストを作り直すため、既定の timeout ではその起動を TIMED_OUT とみなしやすい。
ジョブの timeout は PR で 60 分、main への push と手動実行で 180 分とする。
PR の実行は後の push で取り消し、main の実行は取り消さない。

PIT にはスコアの閾値を置かない。
ジョブは、テストの失敗などで PIT が完走しないときだけ失敗し、結果は HTML と XML のレポートを artifact で確かめる。
スコアの閾値は、main への push で基準値がたまってから別の ADR で決める。

coverage の下限を次の値にする。
全体の値は実測の少し下に、クラスやファイルごとの値は実測の最小の少し下に置く。

| 対象                                        | branch | line |
| ------------------------------------------- | ------ | ---- |
| バックエンドの全体（JaCoCo の bundle）      | 88%    | 95%  |
| バックエンドのクラスごと（JaCoCo の CLASS） | 65%    | 70%  |
| フロントエンドの全体                        | 88%    | 95%  |
| フロントエンドのファイルごと                | 60%    | 65%  |

計測する分岐や行がないクラスとファイルは、クラスやファイルごとの下限を検査しない。
対象から外すのは、これまでどおり生成コードと起動の処理だけとし、低いクラスやファイルを個別に外さない。
下限を割ったときは、ローカルと CI の `be-test` と `fe-coverage` が失敗する。

フロントエンドの全体の下限は `vite.config.ts` の `thresholds` で、ファイルごとの下限は `task fe-coverage` が `coverage-summary.json` から検査する。
Vite+ 0.3.3 の Vitest 4.1 の `perFile` は全体の下限をそのままファイルごとに当てるだけで、ファイルごとに別の値を持てないためである。

## Consequences

### Positive

- 業務コードを変える PR ごとに、変えたクラスのテストの検出力をレポートで確かめられる。
- 差分のクラスとそのパッケージのテストに絞るため、PR での PIT の所要時間は変えたクラスの数と、そのパッケージのテストの重さで決まる。
  ドメインのクラスだけを変える PR は、Spring を起動しない単体テストだけで変異を確かめる。
- テストのないクラスやファイルを加えると、全体の余裕があっても失敗する。
- 全体の line の下限により、分岐のないコードのテストの抜けも検出する。

### Negative

- PR ごとに PIT が走り、Actions の実行時間が増える。
- 差分のクラスだけを変異させるため、変えていないクラスの検出力の低下は main への push の全体の実行まで分からない。
- PR ではほかのパッケージのテストを使わないため、別のパッケージの結合テストだけが殺す変異は PR のレポートで生き残る。
  正しい値は main の全体の実行で確かめる。
- `com.example.demo` の直下のクラスを変えると全テストを使い、PR の実行が全体の実行に近づく。
- minion が 1 つのため、全体の実行は変異の数に比例して長い。
- 下限の余裕が小さく、低いクラスやファイルを触る変更で、テストを足す作業が生じることがある。
- フロントエンドのファイルごとの下限が Vitest の設定でなく Task にあり、下限の置き場所が 2 つに分かれる。

### Neutral

- PIT のジョブは必須チェックでないため、失敗しても merge を止めない。
  失敗はレビューで確かめる。
- 下限は実測に合わせた値であり、テストが増えたら上げる。
- Vitest の `perFile` にファイルごとの値を渡せる版へ上がったら、フロントエンドのファイルごとの下限を `vite.config.ts` へ移す。

## Alternatives Considered

### Alternative 1: PIT を Backend CI のジョブとして毎回実行する

- Description：Backend CI に PIT のジョブを置き、PR と push で実行する。
- Pros：ワークフローが 1 つで済む。
- Cons：Backend CI の必須チェックと同じ実行に入り、PIT の遅さと失敗が必須の結果の確認を遅らせる。

### Alternative 2: PIT のスコアに閾値を置いて必須チェックにする

- Description：スコアが閾値を下回る PR を拒否する。
- Pros：テストの検出力の低下を自動で止められる。
- Cons：基準値がなく、閾値を根拠のある値にできない。

### Alternative 3: PR でも全体を変異させる

- Description：差分を求めず、毎回すべてのクラスを変異させる。
- Pros：変えていないクラスの検出力の低下も PR で分かる。
- Cons：業務コードが増えるほど所要時間が伸び、変えたクラスの結果を探しにくい。

### Alternative 4: 全体の branch の下限だけを上げる

- Description：下限の種類を増やさず、全体の branch を 88% に上げる。
- Pros：設定の変更が 1 か所で済む。
- Cons：テストのないクラスやファイルが全体の余裕に隠れる問題が残る。

## References

- [ADR-012: プロパティベーステストとミューテーションテストを採用する](ADR-012-adopt-property-based-and-mutation-testing.md)
- [ADR-027: Frontend のテスト基盤を標準化する](ADR-027-adopt-frontend-testing-stack.md)
- [ADR-063: Gradle の依存キャッシュを MIT の範囲で restore-keys 付きに復元する](ADR-063-restore-gradle-cache-with-restore-keys-on-mit-caching.md)
- [JaCoCo: Check](https://www.jacoco.org/jacoco/trunk/doc/check-mojo.html)
- [Vitest: coverage.thresholds](https://vitest.dev/config/coverage)
- `.github/workflows/mutation-test.yml`
- `backend/build.gradle`
- `frontend/vite.config.ts`
- `Taskfile.yml`
