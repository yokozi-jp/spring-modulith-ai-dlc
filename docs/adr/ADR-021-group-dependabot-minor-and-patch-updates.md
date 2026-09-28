# ADR-021: Dependabot の minor/patch 更新をグループ化し auto-merge する

## Status

Proposed

## Date

2026-09-29

## Context

ADR-017 で `main` のブランチ保護 ruleset を有効化し、必須チェックを最新の `main` に対して要求する厳格モード（`strict_required_status_checks_policy: true`、GitHub の「Require branches to be up to date before merging」に相当）を採用した。

この厳格モードは、並行して開いている Pull Request のうち一件をマージするたび、残りの Pull Request を base より古い状態（out-of-date）にする。各 Pull Request は最新の `main` を取り込んで必須チェックを再実行しないとマージできない。

Dependabot は既定で、更新可能な依存ごとに個別の Pull Request を作る。このリポジトリは gradle、npm、github-actions、docker、docker-compose の五つのエコシステムを weekly で更新するため、毎週まとまった本数の Pull Request が同時に開く。実際に、tomcat 関連三件（gradle）、GitHub Actions 三件、docker 一件など、複数の Dependabot Pull Request が同時に滞留した。

この二つが重なると、Dependabot の Pull Request を一件マージするたびに残りが out-of-date になり、一件ずつ更新と必須チェック再実行を繰り返す手作業（rebase cascade）が発生する。GitHub 公式もこの負荷への対処として、更新のグループ化を第一の手段として挙げている。

既存の Dependabot 設定には、リリース直後の不正パッケージを避ける cooldown（7 日）、Spring Boot 管理コアと歩調を合わせるための OpenTelemetry の pin、非 LTS を避けるための amazoncorretto の major 無視があり、これらは維持する必要がある。

## Decision

各エコシステムに `minor-and-patch` グループを定義し、`update-types` に `minor` と `patch` を含める。

これにより、各エコシステムの minor 更新と patch 更新は一件の Pull Request に集約する。major 更新はグループから外し、従来どおり個別の Pull Request として出す。破壊的変更を含みうる major は、まとめずに人が個別に判断する。

グループ化は gradle、npm、github-actions、docker、docker-compose の五エコシステムすべてに適用する。既存の cooldown、OpenTelemetry の pin、amazoncorretto の major 無視は変更しない。

加えて、patch と minor の Dependabot Pull Request に auto-merge を設定する。GitHub Actions ワークフロー（`dependabot-auto-merge.yml`）が、Dependabot が作成した Pull Request のうち `dependabot/fetch-metadata` の `update-type` が `version-update:semver-major` でないものに `gh pr merge --squash --auto` を実行し、必須チェックがすべて通った時点で GitHub に squash マージさせる。`fetch-metadata` の `update-type` はその Pull Request が行う最も高い semver 変更を返すため、グループ Pull Request に major が一つでも混じれば major と判定され、auto-merge の対象から外れる。major はグループ外の個別 Pull Request として出るため、いずれの経路でも major は人が判断する。

リポジトリ設定で auto-merge を許可する（`allow_auto_merge: true`）。この設定は可逆で、無効化すればワークフローの `--auto` は失敗するだけで既存 Pull Request の状態は変わらない。

## Consequences

### Positive

- 毎週開く Dependabot Pull Request の本数が減り、厳格モード下の rebase cascade でさばく対象が減る。
- 関連する依存の minor/patch を一件のまとめた変更として確認でき、レビューと必須チェックの重複実行が減る。
- major 更新は個別のまま残るため、破壊的変更を見落とさずに判断できる。
- patch/minor のマージ操作が無人化され、必須チェック通過後の手作業が消える。

### Negative

- 一件の Pull Request に複数の依存更新が混ざるため、どの更新が特定の問題を招いたかの切り分けは、個別 Pull Request のときより手間が増える。
- グループ内のいずれか一つの更新が必須チェックを落とすと、その Pull Request 全体がマージできず、健全な更新の取り込みも足止めされる。
- auto-merge は必須チェックが緑なら人の確認なしにマージする。現在の必須チェックはセキュリティスキャン（betterleaks、Semgrep、Trivy、zizmor）と PR タイトル検証であり、バックエンドの Test & Coverage やイメージビルドは必須チェックに含まれていない。したがって、テストが拾う種類の退行を無人で `main` に入れる穴が残る。テストを必須チェックへ加えるかは別途の判断とする。
- サプライチェーン経由の悪性更新を人の確認なしに取り込む窓が開く。cooldown（7 日）とセキュリティスキャン必須で窓は狭いが、スキャナが検知できない変更は通りうる。

### Neutral

- この変更は `main` にマージされて初めて有効になる。反映後の weekly 実行、または手動の "Check for updates" で、既存の個別 Pull Request がグループ Pull Request へ再編成され、古い個別 Pull Request は Dependabot が自動でクローズする。
- グループ化しても厳格モードの out-of-date 判定自体は消えない。本数を減らして緩和するだけであり、更新の連鎖を完全に無くすにはマージキュー等の別の手段が要る（この決定の対象外）。
- auto-merge を設定しても、厳格モード下では out-of-date になった Pull Request のブランチは自動更新されない。base が進むと auto-merge は発火せず滞留しうる。滞留の解消（ブランチ自動更新やマージキュー）はこの決定の対象外とする。

## Alternatives Considered

### 厳格モード（strict）を無効化する

- **Description**：`strict_required_status_checks_policy` を `false` にし、out-of-date でもマージ可能にする。
- **Pros**：更新の強制がなくなり、rebase cascade が消える。
- **Cons**：古い `main` に対して通った必須チェックのままマージでき、承認からマージまでに `main` が動いた組み合わせを誰も検証しない。ADR-017 のトランクベースと linear history の意図に反する。

### auto-merge を major にも適用する

- **Description**：major を含むすべての Dependabot 更新を auto-merge の対象にする。
- **Pros**：マージの手作業が完全になくなる。
- **Cons**：major は破壊的変更を含みうるため、無人でのマージはリスクが高い。patch/minor のみを auto-merge の対象とし、major は人が判断する（採用した Decision のとおり）。

### マージキュー（merge queue）を導入する

- **Description**：承認済み Pull Request をキューに積み、最新 `main` と先行 Pull Request を合わせた状態で検証してからマージする。
- **Pros**：厳格モードの安全性を保ったまま rebase cascade を根本的に解消する。
- **Cons**：必須チェックを出す各ワークフローの `merge_group` イベント対応が必要で、PR タイトル検証チェックの扱いなど追加調整を要する。並行 Pull Request が恒常的に増えた段階での導入が妥当で、現状の本数削減が先。

## References

- [GitHub Docs: Optimizing the creation of pull requests for Dependabot version updates](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/optimizing-pr-creation-version-updates)
- [GitHub Docs: Configuration options for the dependabot.yml file](https://docs.github.com/en/code-security/dependabot/dependabot-version-updates/configuration-options-for-the-dependabot.yml-file)
- [GitHub Blog: Tame Dependabot — group your updates, slow the cadence, keep security fast](https://github.blog/security/supply-chain-security/tame-dependabot-group-your-updates-slow-the-cadence-keep-security-fast/)
- [ADR-017: トランクベース開発とリポジトリ保護を採用する](./ADR-017-adopt-trunk-based-repository-governance.md)
- [`.github/dependabot.yml`](../../.github/dependabot.yml)
- [`.github/workflows/dependabot-auto-merge.yml`](../../.github/workflows/dependabot-auto-merge.yml)
- [dependabot/fetch-metadata](https://github.com/dependabot/fetch-metadata)
