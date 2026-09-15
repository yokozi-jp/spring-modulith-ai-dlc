# ADR-017: トランクベース開発とリポジトリ保護を採用する

## Status

Proposed

## Date

2026-09-15

## Context

このリポジトリには CI と依存関係更新の自動化がある一方で、コード所有者、コントリビューション手順、脆弱性報告経路、ブランチ保護の共通規約がなかった。

長期間存続する開発ブランチを増やすと、単一のデプロイ単位であるモジュラーモノリスの変更が分岐し、統合時の差分と検証量が増える。

`main` への直接 push を許すと、レビューと既存の CI を通らない変更を防げない。

CODEOWNERS に利用できることをリポジトリから確認できた所有者は `@yokozi-jp` だけであり、未作成のチームを所有者として指定するとレビュー要求が機能しない。

## Decision

長期ブランチを `main` だけに限定するトランクベース開発を採用する。

変更は最新の `main` から作った短命な作業ブランチで行い、Pull Request を経て squash merge する。

GitHub ruleset で `main` への直接 push、force push、削除を禁止し、一件以上の承認、コード所有者の承認、会話の解決、必須チェックの成功、linear history を要求する。

Pull Request のタイトルを squash commit の Conventional Commit メッセージとして扱う。

CODEOWNERS は backend、frontend、docker と infrastructure、`.kiro` と `aidlc`、GitHub 設定の境界ごとに規則を分ける。

有効なチーム識別子ができるまでは、各境界に確認済みの `@yokozi-jp` を割り当てる。

脆弱性は公開 Issue へ書かず、GitHub Security Advisory の非公開報告経路で受け付ける。

## Consequences

### Positive

- すべての変更がレビューと CI を通り、`main` を常に統合可能な状態へ保ちやすくなる。
- ファイル境界ごとのレビュー責任が CODEOWNERS から判別できる。
- Pull Request のテンプレートによって変更内容、検証結果、ブロッカーをリリース前に確認できる。
- 脆弱性の詳細を公開せずに報告できる。

### Negative

- GitHub ruleset と Private Vulnerability Reporting は、リポジトリ管理者が GitHub 側でも有効化する必要がある。
- 所有者が一名の間は領域別ルールを分けても職務分離にならず、その所有者が不在だとレビューが止まる。
- squash merge では作業ブランチ上の個々のコミット履歴が `main` に残らない。

### Neutral

- 将来チームを作成した時点で、CODEOWNERS の各規則を実在する `@owner/team` へ置き換える。
- パス条件付きの CI は変更が該当するときだけ実行されるため、ruleset の常時必須チェックと条件付き品質ゲートを分けて管理する。

## Alternatives Considered

### Git Flow

- **Description**：`develop`、release branch、hotfix branch を長期運用する。
- **Pros**：複数の本番バージョンを並行保守しやすい。
- **Cons**：現時点では単一リリース系列しかなく、ブランチ間の統合と重複検証だけが増える。

### GitHub Flow without branch protection

- **Description**：Pull Request を推奨するが、`main` への直接 push は技術的に許可する。
- **Pros**：緊急変更の手順が短い。
- **Cons**：規約を迂回できるため、レビューと CI を必須にできない。

### 未作成の領域別チームを CODEOWNERS に指定する

- **Description**：backend、frontend、infra、`.kiro` に異なるチーム名を先に記載する。
- **Pros**：将来の職務分担をファイル上で表現できる。
- **Cons**：存在しない所有者へのレビュー要求は機能せず、現在の保護を弱める。

## References

- [GitHub Docs: About rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets)
- [GitHub Docs: About code owners](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [GitHub Docs: Configuring private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/working-with-repository-security-advisories/configuring-private-vulnerability-reporting-for-a-repository)
- [`CONTRIBUTING.md`](../../CONTRIBUTING.md)
- [ブランチ保護設定](../branch-protection.md)
