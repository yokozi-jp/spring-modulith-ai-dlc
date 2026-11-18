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
Trivy は修正版のある脆弱性だけを出し、開発依存を除く（[ADR-029](ADR-029-handle-frontend-dev-dependency-vulnerabilities.md)）。
検出はあっても Pull Request を止めず、Code Scanning で確認する。

[OWASP dep-scan](https://github.com/owasp-dep-scan/dep-scan) は、CycloneDX の SBOM かソースを入力に、脆弱性、到達可能性、ライセンスを調べる OSS である。
Trivy とは別の脆弱性データベース（vdb）を使うので、Trivy が拾わない検出や、拾い方の違いを確かめられる。

2026-11-19 に、公開イメージ `ghcr.io/owasp-dep-scan/dep-scan:v6.3.2` を実際に動かして次を確かめた。

- backend の既存 SBOM（`backend/build/reports/cyclonedx/application.cdx.json`）を `--bom` で渡すと検査できる。検出は 0 件だった。
- 既知の脆弱な `log4j-core@2.14.1` を入れた SBOM では、CVE-2021-44228 など 8 件を検出した。
- frontend のディレクトリを `-t js` で渡すと、cdxgen がロックファイルから SBOM を作り、59 件（重複を除くと 49 件）を検出した。開発依存も対象になる。
- 検出があっても終了コードは 0 だった。出力は VDR（CycloneDX JSON）、HTML、テキストで、SARIF は出ない。
- 既定の vdb は展開後 42 GiB で、ソースだけの検査には大きすぎる。`--vdb-scope app` だと展開後 4.4 GB で、圧縮は約 270 MB だった。
- 版 6.3.2 の CLI は動詞を持たない（`depscan --src ...`）。README の `depscan scan` は未リリースの記述である。

## Decision

dep-scan を Trivy と並べて加え、Trivy は置き換えない。

- イメージは `v6.3.2` を digest で固定して `docker run` する。
- backend は `task be-sbom` の SBOM を、frontend はディレクトリを入力にする。どちらも `--vdb-scope app` で vdb を app-only に絞る。
- 入口を `task scan-depscan`、`scan-depscan-backend`、`scan-depscan-frontend` に置く。ローカルの vdb は `DEPSCAN_VDB_HOME`（既定は `~/.cache/depscan-vdb`）に残して使い回す。
- `depscan.yml` が同じ Task を呼び、`ci.yml` から呼ぶ。`CI gate` の `needs` に加える。
- 検出ではジョブを失敗させない。結果はジョブのログと、artifact の `build/depscan/` で確認する。SARIF が出ないので Code Scanning には送らない。
- CI で vdb を `actions/cache` に保存しない。毎回の取得が約 270 MB で、保存の費用に見合うか、運用して確かめる。

## Consequences

### Positive

- Trivy と別の vdb で、見落としを減らせる。
- CycloneDX の VDR が artifact に残り、後から SBOM と突き合わせられる。

### Negative

- 2 つのツールの検出が重なり、同じ依存の指摘を 2 度読む。
- frontend は開発依存も出すため、Trivy より件数が多く、ADR-029 が除いた指摘も出る。
- 検出は SARIF にならず、Code Scanning のアラートとして追跡できない。
- CI の実行ごとに vdb の取得で数分かかる。

### Neutral

- 検出で失敗させないので、今は運用を見て、必須にするかを別の判断で決める。
- 版の更新は、`Taskfile.yml` の版と digest を手で直す（Dependabot はこの形を追わない）。
- `--bom-profile` による到達可能性の解析と、ライセンス監査は使わない。

## Alternatives Considered

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
- [ADR-029: frontend の開発依存の脆弱性を扱う](ADR-029-handle-frontend-dev-dependency-vulnerabilities.md)
- [ADR-087: 必須チェックを集約ジョブ CI gate を中心にまとめる](ADR-087-gate-required-checks-with-single-aggregate-job.md)
