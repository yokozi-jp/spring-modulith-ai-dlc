# リリース管理

## 管理対象

このリポジトリは全体を一つのリリース単位として扱う。

release-please は次のファイルを同じ Release Pull Request で更新する。

- `.release-please-manifest.json`：release-please が追跡する直近の版
- `version.txt`：`simple` strategy が使う主版ファイル
- `backend/build.gradle`：ビルドと実行時に使うアプリケーション版
- `CHANGELOG.md`：Conventional Commits から生成したリリースノート

frontend は private package であり、独立したタグと版系列を持たない。

版情報の一致は次のコマンドで確認する。

```bash
task release-check
```

## GitHub token の設定

`.github/workflows/release-please.yml` は repository secret `RELEASE_PLEASE_TOKEN` を必要とする。

GitHub の fine-grained personal access token を次の条件で発行する。

- Resource owner：このリポジトリを所有する user または organization
- Repository access：`spring-modulith-ai-dlc` だけ
- Contents：Read and write
- Issues：Read and write
- Pull requests：Read and write
- Expiration：組織の規約内で最短の実用的な期限

発行した token を repository secret `RELEASE_PLEASE_TOKEN` として保存し、期限前にローテーションする。

actions workflow の `GITHUB_TOKEN` で作った Pull Request は、再帰実行を防ぐ GitHub の制約によって通常の `pull_request` workflow を起動しない。

Release Pull Request に通常の CI と必須チェックを実行するため、専用 token を使う。

GitHub App を運用できる場合は、長期 PAT の代わりに installation access token を実行時に発行する構成へ移行する。

## 版の決まり方

release-please は `main` に入った Conventional Commits から次版を決める。

- `fix`：patch を上げる。
- `feat`：minor を上げる。
- `!` または `BREAKING CHANGE:`：major を上げる。
- `docs`、`test`、`chore` だけでは通常、新しいリリースを作らない。

導入時の版は `0.0.1` である。

`release-please-config.json` の bootstrap SHA より前の履歴は、初回 CHANGELOG の生成対象にしない。

初回リリース後は bootstrap SHA が無視されるため、後続の保守変更で削除できる。

## リリース手順

通常の変更を短命なブランチから `main` へ squash merge する。

`main` への push を受けた Release Please workflow が、Release Pull Request を作成または更新する。

Release Pull Request では次版、CHANGELOG、版ファイルの一致、通常の CI を確認する。

リリースする時点で Release Pull Request を `main` へ merge する。

次の workflow 実行が `v<version>` タグと GitHub Release を作る。

成果物の公開と本番デプロイはこの workflow の責務に含めない。

## 例外的な版指定

自動計算と異なる版が必要な場合は、理由を記録した Conventional Commit の footer に `Release-As:` を指定する。

```text
chore(release): align initial public version

Release-As: 1.0.0
```

この指定は通常運用では使わず、誤ったタグの回復や公開版の初期化など、次版を明示する必要がある場合に限定する。

## 障害時の確認

Release Pull Request が作られない場合は、workflow の実行結果、token の期限と権限、Conventional Commit の type、bootstrap SHA 以降の履歴を確認する。

版ファイルの不一致は `task release-check` で検出し、通常の変更で手作業修正せず Release Pull Request を再実行する。

手動再実行には Release Please workflow の `workflow_dispatch` を使う。

タグや GitHub Release を削除して履歴を書き換える操作は自動化せず、影響を確認してから個別に判断する。

## 参考資料

- [release-please の manifest mode](https://github.com/googleapis/release-please/blob/main/docs/manifest-releaser.md)
- [release-please-action](https://github.com/googleapis/release-please-action)
- [GitHub Actions の `GITHUB_TOKEN`](https://docs.github.com/en/actions/concepts/security/github_token)
- [ADR-018](adr/ADR-018-automate-semantic-releases.md)
