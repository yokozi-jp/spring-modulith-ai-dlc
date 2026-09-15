# ADR-018: release-please でセマンティックリリースを自動化する

## Status

Proposed

## Date

2026-09-15

## Context

コミットメッセージは Conventional Commits で検証しているが、バックエンドのバージョンは `0.0.1-SNAPSHOT` に固定され、CHANGELOG、タグ、GitHub Release を作る共通手順がなかった。

手作業でバージョンとリリースノートを更新すると、コミットの意味と版番号がずれ、同じ変更を複数箇所へ転記する必要がある。

一方で、コミットが `main` に入るたびに即時公開すると、版番号とリリースノートをレビューする機会を失う。

現在の配布単位は Spring Boot バックエンドを含むリポジトリ全体であり、private な frontend package を独立公開する要件はない。

## Decision

release-please の manifest mode と `simple` release type を使い、リポジトリ全体を一つのリリース単位として扱う。

現在版を `0.0.1` とし、導入前の履歴を初回 CHANGELOG に取り込まないよう、導入時の `main` の commit `13d6a221172888c821ed56d3e376a1f6b0d1146c` を bootstrap SHA にする。

release-please は Conventional Commits から SemVer の次版を計算し、ルートの `CHANGELOG.md`、manifest、`version.txt`、`backend/build.gradle` の版番号を Release Pull Request で更新する。

開発時も `-SNAPSHOT` を付けず、manifest、`version.txt`、ソース上の版番号を一致させる。

Release Pull Request を `main` へマージしたとき、`v<version>` タグと GitHub Release を作成する。

workflow は検証済み commit SHA に固定した release-please action を使い、`contents: write`、`pull-requests: write`、`issues: write` だけをジョブへ付与する。

Release Pull Request でも通常の CI を起動できるよう、workflow には repository secret `RELEASE_PLEASE_TOKEN` からリポジトリ限定の fine-grained personal access token を渡す。

この自動化は版採番、CHANGELOG、タグ、GitHub Release だけを担い、成果物の公開と本番デプロイは行わない。

## Consequences

### Positive

- `fix`、`feat`、破壊的変更から SemVer の更新幅を一貫して算出できる。
- CHANGELOG、ソースの版番号、タグ、GitHub Release の対応を一つの Release Pull Request でレビューできる。
- リリースの実行は Release Pull Request のマージとして監査履歴に残る。
- 通常の変更を `main` へ統合する流れと、リリースする判断を分離できる。

### Negative

- Conventional Commits の type と破壊的変更の記法が誤っていると、次版と CHANGELOG も誤る。
- `RELEASE_PLEASE_TOKEN` の発行、ローテーション、失効を管理する必要がある。
- Release Pull Request をマージするまで新しいタグと GitHub Release は作られない。

### Neutral

- frontend を独立配布する要件が生じた場合は、manifest に別 package を追加し、版系列を分ける判断が必要になる。
- 初回リリース後は bootstrap SHA が無視されるため、設定から削除できる。
- GitHub Release の作成後に成果物を公開する処理は、release-please の出力を条件にした別ジョブとして追加できる。

## Alternatives Considered

### 手動リリース

- **Description**：担当者が版番号、CHANGELOG、タグ、GitHub Release を個別に更新する。
- **Pros**：追加の GitHub Action と token が不要になる。
- **Cons**：転記漏れと版番号の不整合を機械的に防げない。

### semantic-release

- **Description**：`main` への変更から CI 内で直ちに版採番と公開を行う。
- **Pros**：Release Pull Request を操作せず完全自動で公開できる。
- **Cons**：版番号とリリースノートを公開前に Pull Request として確認する運用に合わない。

### Release Drafter

- **Description**：Pull Request のラベルから GitHub Release の草稿を作る。
- **Pros**：リリースノートの草稿を継続的に確認できる。
- **Cons**：ソースの版番号更新と SemVer の算出を別の仕組みで補う必要がある。

### frontend と backend の独立リリース

- **Description**：二つの package として別々の版番号とタグを発行する。
- **Pros**：各成果物を独立した頻度で公開できる。
- **Cons**：frontend は private package であり、現時点では独立版を運用する根拠がない。

## References

- [release-please: Manifest releaser](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md)
- [release-please: Updating arbitrary files](https://github.com/googleapis/release-please/blob/main/docs/customizing.md#updating-arbitrary-files)
- [release-please-action](https://github.com/googleapis/release-please-action)
- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)
- [Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html)
