---
type: Architecture Decision Record
title: 'ADR-021: Dependabot の minor/patch 更新をグループ化し auto-merge する'
description: 厳格モードでの Pull Request 滞留を抑えるため、Dependabot の minor/patch 更新をグループ化し auto-merge する決定。
tags: [adr, dependabot, ci, automation]
---

# ADR-021: Dependabot の minor/patch 更新をグループ化し auto-merge する

## Status

Proposed

## Date

2026-09-29

## Context

ADR-017 で `main` のブランチ保護 ruleset を有効化し、必須チェックを最新の `main` に対して要求する厳格モード（`strict_required_status_checks_policy: true`、GitHub の「Require branches to be up to date before merging」に相当）を採用した。

この厳格モードは、並行して開いている Pull Request のうち一件をマージするたび、残りの Pull Request を base より古い状態（out-of-date）にする。
各 Pull Request は最新の `main` を取り込んで必須チェックを再実行しないとマージできない。

Dependabot は既定で、更新可能な依存ごとに個別の Pull Request を作る。
このリポジトリは gradle、npm、github-actions、docker、docker-compose の五つのエコシステムを weekly で更新するため、毎週まとまった本数の Pull Request が同時に開く。
実際に、tomcat 関連三件（gradle）、GitHub Actions 三件、docker 一件など、複数の Dependabot Pull Request が同時に滞留した。

この二つが重なると、Dependabot の Pull Request を一件マージするたびに残りが out-of-date になり、一件ずつ更新と必須チェック再実行を繰り返す手作業（rebase cascade）が発生する。
GitHub 公式もこの負荷への対処として、更新のグループ化を第一の手段として挙げている。

既存の Dependabot 設定には、リリース直後の不正パッケージを避ける cooldown（7 日）、Spring Boot 管理コアと歩調を合わせるための OpenTelemetry の pin、非 LTS を避けるための amazoncorretto の major 無視があり、これらは維持する必要がある。

## Decision

各エコシステムに `minor-and-patch` グループを定義し、`update-types` に `minor` と `patch` を含める。

これにより、各エコシステムの minor 更新と patch 更新は一件の Pull Request に集約する。
major 更新はグループから外し、従来どおり個別の Pull Request として出す。
破壊的変更を含みうる major は、まとめずに人が個別に判断する。

グループ化は gradle、npm、github-actions、docker、docker-compose の五エコシステムすべてに適用する。
既存の cooldown、OpenTelemetry の pin、amazoncorretto の major 無視は変更しない。

加えて、patch と minor の Dependabot Pull Request に auto-merge を設定する。
GitHub Actions ワークフロー（`dependabot-auto-merge.yml`）が、Dependabot が作成した Pull Request のうち `dependabot/fetch-metadata` の `update-type` が `version-update:semver-patch` または `version-update:semver-minor` のものに限って `gh pr merge --squash --auto` を実行し、必須チェックがすべて通った時点で GitHub に squash マージさせる。
`fetch-metadata` が更新種別を判定できない場合や、タグを変えずダイジェストだけを更新する場合は auto-merge せず、人が判断する。
major もグループ外の個別 Pull Request として出るため、人が判断する。

リポジトリ設定で auto-merge を許可する（`allow_auto_merge: true`）。
この設定は可逆で、無効化すればワークフローの `--auto` は失敗するだけで既存 Pull Request の状態は変わらない。

auto-merge は必須チェックが通った時点でマージするため、必須チェックの集合が auto-merge の安全性を決める。
現在の ruleset が要求するのは、セキュリティスキャン（betterleaks、Semgrep、Trivy、zizmor）と PR タイトル検証の六件である。
Pull Request #67 を `main` へマージして条件付きチェックを常に報告できる workflow を先に反映した後、`Detect backend changes`、`Detect docker changes`、`Test & Coverage ☕`、`Lint (Spotless + PMD + SpotBugs) ☕`、`Run hadolint 🐳`、`Run docker build --check 🐳`、`Build and test backend image 🐳` の七件を ruleset の必須チェックへ追加する。
変更検知とイメージビルドの前段検査も必須にすることで、前段ジョブが失敗して後続ジョブが `skipped` になった場合に、失敗を素通りさせない。

これらの backend と Docker の CI ジョブは関連パスを触る Pull Request でのみ実行する重いジョブである。
ジョブを走らせる workflow を Pull Request のパスフィルタでスキップすると、GitHub は check run を生成せず、必須チェックが "Expected" のまま Pull Request が恒久的にマージ不能になる。
これを避けるため、Pull Request では workflow のパスフィルタを外して常に起動し、workflow 内の変更検知ジョブが `fetch-depth: 0` で取得済みの base と `HEAD` の差分を判定して、各重いジョブを `if` 条件で実行する。
差分取得に失敗した場合は backend または docker の変更ありとして重いジョブを実行し、安全側へ倒す。
ジョブレベルの `if` でスキップされたジョブは `skipped` の結論を持つ check run を生成し、GitHub のブランチ保護はこれを success と同様に満たされたものとして扱う。
`main` への push では従来どおりパスフィルタで無関係な実行を抑える。

## Consequences

### Positive

- 毎週開く Dependabot Pull Request の本数が減り、厳格モード下の rebase cascade でさばく対象が減る。
- 関連する依存の minor/patch を一件のまとめた変更として確認でき、レビューと必須チェックの重複実行が減る。
- major 更新は個別のまま残るため、破壊的変更を見落とさずに判断できる。
- patch/minor のマージ操作が無人化され、必須チェック通過後の手作業が消える。
- backend の Test & Coverage、静的解析、Dockerfile 検査、イメージビルドが必須チェックになり、これらが拾う退行は auto-merge でも人手マージでも `main` に入る前に止まる。

### Negative

- 一件の Pull Request に複数の依存更新が混ざるため、どの更新が特定の問題を招いたかの切り分けは、個別 Pull Request のときより手間が増える。
- グループ内のいずれか一つの更新が必須チェックを落とすと、その Pull Request 全体がマージできず、健全な更新の取り込みも足止めされる。
- auto-merge は必須チェックが緑なら人の確認なしにマージする。
  この退行検知の穴を塞ぐため Test & Coverage、静的解析、Dockerfile 検査、イメージビルドを必須チェックに加えるが、必須チェックが検証しない性質（実行時の性能劣化、テストが網羅しない経路の挙動など）は依然として無人で `main` に入りうる。
- サプライチェーン経由の悪性更新を人の確認なしに取り込む窓が開く。
  cooldown（7 日）とセキュリティスキャン必須で窓は狭いが、スキャナが検知できない変更は通りうる。
- backend 非依存の Pull Request では必須のバックエンド CI が `skipped` になり success 扱いとなる。
  変更検知の `git diff` パターンが実際の依存範囲より狭いと、本来テストすべき変更を skip したまま通す誤りが起こりうるため、パターンは push 側のパスフィルタと同一集合に保つ。
- `GITHUB_TOKEN` が直接起こした push は後続の GitHub Actions workflow を起動しない。
  `gh pr merge --auto` は native auto-merge を有効化して後から GitHub がマージするため、この制限がマージ時の push にも適用されるかは公式文書だけでは確定できない。
  初回の自動マージで `main` の push workflow を実測し、起動しない場合は release-please が次の `main` push まで遅延することを受け入れるか、GitHub App installation token へ切り替える。

### Neutral

- `minor-and-patch` のグループ設定は `main` に反映済みである。
  auto-merge と条件付き必須チェックの workflow は Pull Request #67 のマージで有効になる。
- Pull Request #67 のマージ後に ruleset を更新するまで、変更検知二件、backend CI 二件、Docker CI 三件は必須チェックではない。
  先に ruleset を更新すると、パスフィルタが残る古い `main` を基準にした Pull Request で check run が生成されず、"Expected" のままマージ不能になりうるため、この順序を守る。
- グループ化しても厳格モードの out-of-date 判定自体は消えない。
  本数を減らして緩和するだけであり、更新の連鎖を完全に無くすにはマージキュー等の別の手段が要る（この決定の対象外）。
- auto-merge を設定しても、厳格モード下では out-of-date になった Pull Request のブランチは自動更新されない。
  base が進むと auto-merge は発火せず滞留しうる。
  滞留の解消（ブランチ自動更新やマージキュー）はこの決定の対象外とする。

## Alternatives Considered

### 厳格モード（strict）を無効化する

- **Description**：`strict_required_status_checks_policy` を `false` にし、out-of-date でもマージ可能にする。
- **Pros**：更新の強制がなくなり、rebase cascade が消える。
- **Cons**：古い `main` に対して通った必須チェックのままマージでき、承認からマージまでに `main` が動いた組み合わせを誰も検証しない。
  ADR-017 のトランクベースと linear history の意図に反する。

### auto-merge を major にも適用する

- **Description**：major を含むすべての Dependabot 更新を auto-merge の対象にする。
- **Pros**：マージの手作業が完全になくなる。
- **Cons**：major は破壊的変更を含みうるため、無人でのマージはリスクが高い。
  patch/minor のみを auto-merge の対象とし、major は人が判断する（採用した Decision のとおり）。

### マージキュー（merge queue）を導入する

- **Description**：承認済み Pull Request をキューに積み、最新 `main` と先行 Pull Request を合わせた状態で検証してからマージする。
- **Pros**：厳格モードの安全性を保ったまま rebase cascade を根本的に解消する。
- **Cons**：必須チェックを出す各ワークフローの `merge_group` イベント対応が必要で、PR タイトル検証チェックの扱いなど追加調整を要する。
  並行 Pull Request が恒常的に増えた段階での導入が妥当で、現状の本数削減が先。

## References

- [GitHub Docs: Optimizing the creation of pull requests for Dependabot version updates](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/optimizing-pr-creation-version-updates)
- [GitHub Docs: Configuration options for the dependabot.yml file](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)
- [GitHub Blog: Tame Dependabot — group your updates, slow the cadence, keep security fast](https://github.blog/security/supply-chain-security/tame-dependabot-group-your-updates-slow-the-cadence-keep-security-fast/)
- [ADR-017: トランクベース開発とリポジトリ保護を採用する](ADR-017-adopt-trunk-based-repository-governance.md)
- [.github/dependabot.yml](../../.github/dependabot.yml)
- [.github/workflows/dependabot-auto-merge.yml](../../.github/workflows/dependabot-auto-merge.yml)
- [.github/workflows/backend-ci.yml](../../.github/workflows/backend-ci.yml)
- [.github/workflows/hadolint.yml](../../.github/workflows/hadolint.yml)
- [GitHub Docs: Troubleshooting required status checks（skipped は success 扱い）](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/troubleshooting-required-status-checks)
- [GitHub Docs: Triggering a workflow（`GITHUB_TOKEN` による再帰実行の抑止）](https://docs.github.com/en/actions/using-workflows/triggering-a-workflow)
- [dependabot/fetch-metadata](https://github.com/dependabot/fetch-metadata)
