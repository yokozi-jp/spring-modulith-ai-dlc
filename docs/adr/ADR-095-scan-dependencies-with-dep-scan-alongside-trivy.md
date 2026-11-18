---
type: ADR
title: 'ADR-095: 依存関係の脆弱性検査に OWASP dep-scan を Trivy と並べて加える'
description: backend と frontend の依存関係を、Trivy に加えて OWASP dep-scan でも検査する。検出では失敗させず、VDR と HTML を artifact に残す。
tags: [adr, security, ci, dependencies]
---

# ADR-095: 依存関係の脆弱性検査に OWASP dep-scan を Trivy と並べて加える

## Status

Proposed

## Date

2026-11-19

## Context

依存関係の脆弱性検査は Trivy だけが担っている（`task scan-vulns`、`trivy.yml`）。
Trivy は `ignore-unfixed` で修正版のある脆弱性だけを出し、既定で開発依存を除く（[ADR-029](ADR-029-handle-frontend-dev-dependency-vulnerabilities.md)）。
検出はあっても Pull Request を止めず、Code Scanning で確認する。

[OWASP dep-scan](https://github.com/owasp-dep-scan/dep-scan) は、CycloneDX の SBOM かソースを入力に、脆弱性、到達可能性、ライセンスを調べる OSS である。
Trivy とは別の脆弱性データベース（vdb）を使うので、Trivy が拾わない検出や、拾い方の違いを確かめられる。

2026-11-19 に、公開イメージ `ghcr.io/owasp-dep-scan/dep-scan:v6.3.2` を実際に動かして次を確かめた。

- backend の既存 SBOM（`backend/build/reports/cyclonedx/application.cdx.json`）を `--bom` で渡すと検査できる。検出は 0 件だった。
- 既知の脆弱な `log4j-core@2.14.1` を入れた SBOM では、CVE-2021-44228 など 8 件を検出した。
- frontend のディレクトリを `-t js` で渡すと、cdxgen がロックファイルから SBOM を作り、59 件（重複を除くと 49 件）を検出した。開発依存も対象になる。
- 検出があっても終了コードは 0 だった。出力は VDR（CycloneDX JSON）、HTML、テキストで、SARIF は出ない。検出が 0 件のときは VDR を書かない。
- 既定の vdb は展開後 42 GiB で、ソースだけの検査には大きすぎる。`--vdb-scope app` だと展開後 4.4 GB で、圧縮は約 270 MB だった。
- cdxgen がロックファイルを読めず部品が 0 件のときも、終了コードは 0 で終わる。
- 同じ入力で Trivy と検出を突き合わせる比較は、まだしていない。重なりと差は、運用して確かめる。
- 版 6.3.2 の CLI は動詞を持たない（`depscan --src ...`）。README の `depscan scan` は未リリースの記述である。

## Decision

dep-scan を Trivy と並べて加え、Trivy は置き換えない。

- イメージは `v6.3.2` を digest で固定して `docker run` する。
- backend は `task be-sbom` の SBOM を、frontend はディレクトリを入力にする。どちらも `--vdb-scope app` で vdb を app-only に絞る。
- 入口を `task scan-depscan`、`scan-depscan-backend`、`scan-depscan-frontend` に置く。ローカルの vdb は `DEPSCAN_VDB_HOME`（既定は `~/.cache/depscan-vdb`）に残して使い回す。
- `depscan.yml` が同じ Task を呼び、`ci.yml` から呼ぶ。`CI gate` の `needs` に加える。
- 検査した SBOM に部品がないときは、Task が失敗する。
- 検出ではジョブを失敗させない。脆弱性 DB やイメージの取得に失敗したとき（取得は 3 回試して失敗すると終了コード 1 になる）は失敗し、`CI gate` が止まる。Trivy の DB 取得の失敗と同じ扱いで、障害が回復してから再実行する。結果はジョブのログと、artifact の `build/depscan/` で確認する。SARIF が出ないので Code Scanning には送らない。
- CI で vdb を `actions/cache` に保存しない。CI での取得と展開は約 40 秒で、イメージの取得（約 4 GB）の約 50 秒のほうが長い。4.4 GB の展開後の DB を Actions のキャッシュに保存すると、リポジトリのキャッシュの容量を使い切りやすい。

## Consequences

### Positive

- Trivy と別の vdb で、見落としを減らせる。
- 検出があるとき、CycloneDX の VDR が artifact に残り、後から SBOM と突き合わせられる。

### Negative

- 2 つのツールの検出が重なり、同じ依存の指摘を 2 度読む。
- frontend は開発依存も出すため、Trivy より件数が多く、ADR-029 が除いた指摘も出る。
- 検出は SARIF にならず、Code Scanning のアラートとして追跡できない。
- CI の実行ごとに、イメージと vdb の取得で 1 分半ほどかかる（backend と frontend のジョブそれぞれ）。
- 取得先（ghcr.io）が止まると、Pull Request が `CI gate` で止まる。

### Neutral

- 検出で失敗させないので、今は運用を見て、必須にするかを別の判断で決める。
- 版の更新は、`Taskfile.yml` の版と digest を手で直す（Dependabot はこの形を追わない）。
- `--bom-profile` による到達可能性の解析と、ライセンス監査は使わない。

## Alternatives Considered

### Trivy だけを続ける

- **Description**：dep-scan を入れず、Trivy だけで検査する。
- **Pros**：検査が 1 つで、運用の負担が増えない。
- **Cons**：別の脆弱性データベースによる確認がない。重なりの大きさを測っていないため、効果が小さいと分かれば、この案に戻して dep-scan を外す。

### Trivy を dep-scan に置き換える

- **Description**：`trivy.yml` と `scan-vulns*` をやめ、dep-scan だけにする。
- **Pros**：検査が 1 つになり、重なる指摘がなくなる。
- **Cons**：Code Scanning への SARIF が失われる。dep-scan の運用実績がこのリポジトリにまだない。

### dep-scan を Python パッケージで入れる

- **Description**：`pip install owasp-depscan` と cdxgen を CI に入れる。
- **Pros**：Docker を介さない。
- **Cons**：Python と Node.js の版を足して管理する。イメージは cdxgen と一式で digest 固定できる。

### 検出で失敗させる

- **Description**：検出があればジョブを失敗させ、`CI gate` で止める。
- **Pros**：検出を見逃さない。
- **Cons**：frontend の開発依存を含む 49 件で、すぐ止まる。まず運用して件数を見る。

## References

- [OWASP dep-scan](https://github.com/owasp-dep-scan/dep-scan)
- [ADR-029: Frontend 開発依存の既知脆弱性を override で扱う](ADR-029-handle-frontend-dev-dependency-vulnerabilities.md)
- [ADR-087: 必須チェックを集約ジョブ CI gate を中心にまとめる](ADR-087-gate-required-checks-with-single-aggregate-job.md)
