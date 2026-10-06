---
type: ADR
title: 'ADR-063: Gradle の依存キャッシュを MIT の範囲で restore-keys 付きに復元する'
description: Gradle User Home のキャッシュを MIT の範囲に保ったまま、setup-gradle v6.4.0 の external と actions/cache の restore-keys で復元し、保存を main だけに限り、setup-gradle を v6.3.0 から v6.4.0 へ上げることも含む決定。
tags: [adr, ci, gradle, cache]
---

# ADR-063: Gradle の依存キャッシュを MIT の範囲で restore-keys 付きに復元する

## Status

Proposed

## Date

2026-10-06

## Context

Backend CI と E2E の 4 つのジョブ（Lint、Test、Mutation、E2E）は、`gradle/actions/setup-gradle` を `cache-provider: basic` で使っている。
basic は restore-keys を持たない。
キーは `setup-java-{OS}-{arch}-gradle-{hash}` で、hash の入力に `**/*.gradle*` を含むため、ビルドファイルが 1 つ変わると完全一致がなくなり、全依存を取り直す。

ビルドファイルは頻繁に変わる。
release-please が `backend/build.gradle` の `version` を書き換え、feature の変更が `backend/gradle/database.gradle` を書き換える。
PR の run はキャッシュを保存しないため、ビルドファイルを変えた PR は push のたびに空の状態から始まる。

実測では、Test ジョブ 40 件のうち 19 件（47.5%）が空の状態からの開始だった。
内訳は PR が 17/31、main への push が 2/9 である。
期間は 2026-10-04 から 2026-10-06 の約 1.7 日分で、50 run から集計した短い窓である。
hit と miss の差は次のとおりである（CI 高速化の調査報告の 2.3 節）。

- wall time は hit が 293 秒、miss が 383 秒で、差は 90 秒である。
- Lint ジョブは hit が 80 秒、miss が 141 秒で、差は 61 秒である。
- Test ジョブは hit が 280 秒、miss が 367 秒で、差は 87 秒である。

Lint と `Verify migrations` の差は、期間中のテスト本数の増加の影響を受けないため、キャッシュ失効の効果を直接示す。
`task be-test` の差にはテスト増加が混じる。

現在の `cache-provider: basic` は、Enhanced Caching（独自ライセンスの `gradle-actions-caching`）を避ける理由で選ばれていた。
その理由は workflow のコメントにだけ残り、ADR はなかった。
今回の要件も、MIT ライセンスの部品だけを使うことである。

リポジトリの Actions cache は、既定の上限 10 GB を超えている。
キャッシュの保存量を増やす案は採れない。

## Decision

Gradle User Home のキャッシュを、MIT ライセンスの `actions/cache` の restore と save で扱う。
`setup-gradle` は `cache-provider: external` にして、キャッシュの復元も保存もさせない。

`external` は `setup-gradle` v6.4.0 で追加された値であり、固定していた v6.3.0 は受け付けない。
v6.3.0 のソースは `basic` と `enhanced` だけを許可し、それ以外の値には `TypeError` を投げる。
このため、`setup-gradle` を v6.3.0 から v6.4.0 へ上げることを、この決定の一部とする。
固定する commit は、v6.4.0 のタグが指す `3f5f9adaf7d9fecd50b5935e54106014257a94e6` である。
v6.4.0 は 2026-09-28 公開の安定版で、プレリリースではない。

`external` が独自ライセンスの部品を読み込まないことは、v6.4.0 のソースで確認した。
`getCacheService` は、`external` のとき何もしない実装（`NoOpCacheService`）を返して処理を終える。
同梱の `gradle-actions-caching` を読み込む `loadVendoredCacheService` は、`enhanced` の経路でだけ呼ばれる。
公式の文書も、`external` は `setup-gradle` に Gradle User Home の復元と保存をさせない設定だと説明している。

キャッシュの対象は、Gradle User Home のうち `~/.gradle/caches` と `~/.gradle/wrapper` だけにする。
認証情報を含みうる `gradle.properties` や init script、`daemon` と `native` は含めない。

キーは次の形にする。

- key：`gradle-${{ runner.os }}-<ジョブ名>-<backend の *.gradle* と gradle-wrapper.properties の hash>`
- restore-keys：hash を除いた `gradle-${{ runner.os }}-<ジョブ名>-`

hash が変わっても、同じジョブ名の直近の main のキャッシュを復元し、差分の依存だけを取得する。

保存は `github.ref` が `refs/heads/main` で、かつ復元が完全一致でなかったときだけ行う。
保存の key は、復元ステップが返す `cache-primary-key` を使い、hash を再計算しない。
PR は復元だけを行い、キャッシュを書かない。

ジョブ名の部分は、Lint と Test が自分の値（`lint` と `test`）を持ち、Mutation と E2E は Test の値を読み取り専用で借りる。
理由は次の 3 つである。

- 既存の key は上書きされない。
  Lint は Test より先に終わるため、key を共有すると Lint が先に書き、Test の依存を含むキャッシュが保存されない。
- E2E は `push` で動かない。
  main の key を自分では作れない。
- Mutation は `workflow_dispatch` でだけ動く。
  main の key を安定して作れない。

`setup-gradle` の公式 docs は、`external` のとき Job Summary にキャッシュを外部で処理したと表示すると説明している。
v6.4.0 は、Gradle のバージョンの対応状況を Job Summary に注記する（警告または通知で、ビルドの動作は変えない）。
このリポジトリの Gradle は 9.8.0 である。

## Consequences

### Positive

- hash が変わっても直近の main のキャッシュを復元でき、差分の依存だけを取得する。
- miss の run で、約 65 秒から 90 秒を短縮できる見込みである（Lint の差 61 秒、Test の差 87 秒、wall time の差 90 秒から見積もった）。
- miss の割合が 47.5% だとすると、平均で約 30 秒から 45 秒の短縮になる。
  確度は中程度から高い。
- PR がキャッシュを書かないため、信頼できない ref がキャッシュを汚染する経路がない。
- 独自ライセンスの部品を使わない。

### Negative

- restore-keys で古いキャッシュを引き継ぐため、キャッシュに掃除の仕組みがなく、エントリが肥大しうる。
  上限は、ジョブ名の部分を変えて作り直すか、Enhanced Caching を見直すことで解消する。
- 4 つのジョブに同じ YAML が重複する。
  composite action や reusable workflow は、新しい抽象として作らない。
- 新しい hash ごとに main が約 400 MB を書く。
  頻度は現在と同じで純増はないが、上限超過の問題は残る。
- 最初のキャッシュは、この変更が main に入ったあとに作られる。
  この変更の PR の run は、空の状態から始まる。
- main の run が失敗またはキャンセルされると保存されない。
  次に成功した main の run が保存する。
- 既存の `setup-java-*` のエントリは参照されなくなり、期限切れで消える。
- `setup-gradle` を v6.4.0 へ上げるため、Job Summary の文言が変わり、Gradle のバージョンの注記が出ることがある。

### Neutral

- 変更後の最初の PR で、復元ステップのログの `Cache restored from key` と `cache-hit` を確かめる。
  ビルドファイルを変えた PR では、古い hash のキーから復元し、`cache-hit` が false になるのが期待どおりである。
- restore-keys で部分復元したときの短縮量は、まだ測っていない。
- `.github/dependabot.yml` の `github-actions` が、新しい 2 つの固定を更新する。
- 部分復元の短縮量が足りなければ、選択肢6の version の移動を検討する。

## Alternatives Considered

### 選択肢1: Enhanced Caching

- **Description**：`cache-provider` の行を消し、Enhanced Caching を使う。
- **Pros**：公開リポジトリでは無料で、restore keys とコミット単位の保存を備え、差分は 4 行で済む。
- **Cons**：`gradle-actions-caching` が独自ライセンスであり、MIT の部品だけを使う要件に合わないため採らない。

### 選択肢2: basic のまま維持する

- **Description**：`cache-provider: basic` を続ける。
- **Pros**：変更がない。
- **Cons**：Test ジョブの 47.5% が空の状態から始まり、miss の run で約 90 秒を失うため採らない。

### 選択肢3: v6.3.0 のまま cache-disabled を使う

- **Description**：`setup-gradle` を v6.3.0 に固定したまま `cache-disabled: true` にし、`actions/cache` で保存と復元をする。
- **Pros**：バージョンを上げずに済む。
- **Cons**：公式 docs によると Job Summary がキャッシュ無効を示唆し、外部のキャッシュを使っている意図を誤って伝えるため採らない。

### 選択肢4: コミットごとの key

- **Description**：key に `github.sha` を含め、restore-keys で直近を復元する。
- **Pros**：常に最新のキャッシュを保存できる。
- **Cons**：コミットごとに約 400 MB を書き、すでに超過している上限をさらに圧迫するため採らない。

### 選択肢5: 4 つのジョブで 1 つの key を共有する

- **Description**：ジョブ名の部分を設けず、全ジョブで同じ key を使う。
- **Pros**：key の設計が単純で、保存量が少ない。
- **Cons**：先に終わる Lint が key を書き、Test の依存を含むキャッシュが保存されないため採らない。

### 選択肢6: release-please の extra-files による version の移動

- **Description**：`backend/build.gradle` の `version` を、`*.gradle*` に一致しない `backend/gradle.properties` へ移す。
- **Pros**：release-please のたびに起きるキャッシュ失効を止められる。
- **Cons**：`database.gradle` の変更など、依存に由来する失効は止まらない。
  `release-check`、`release-please-config.json`、関連する docs の変更も要る。
  この決定を置き換える案ではなく補完策であり、restore-keys だけでは足りないと分かった時点で検討する。

## References

- CI 高速化の調査報告（2026-10-06、リポジトリの外。2.3 節の実測と 3 節の B1）
- [gradle/actions, setup-gradle, Using an external cache provider](https://github.com/gradle/actions/blob/v6.4.0/docs/setup-gradle.md#using-an-external-cache-provider)
- [gradle/actions, v6.4.0 の cache-service-loader.ts](https://github.com/gradle/actions/blob/v6.4.0/sources/src/cache-service-loader.ts)
- [actions/cache, restore](https://github.com/actions/cache/tree/main/restore)
- [actions/cache, save](https://github.com/actions/cache/tree/main/save)
- [CI とテストの対応](../tooling/lint-and-test.md)
