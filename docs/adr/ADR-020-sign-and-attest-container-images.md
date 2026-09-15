# ADR-020: コンテナイメージを署名し provenance を検証する

## Status

Proposed

## Date

2026-09-15

## Context

バックエンドの依存関係には CycloneDX SBOM と Trivy の検査があり、Docker base image と GitHub Actions は digest または commit SHA に固定している。

一方、アクティブなワークフローはコンテナを registry へ公開せず、署名と provenance を生成していない。
本番 CD のひな型は ECR の immutable digest を昇格する方針を持つが、署名と provenance の検証は fail-closed の TODO のままである。

タグだけでは、同じ名前が後から別のイメージを指す可能性がある。
digest の存在だけでも、そのイメージをどの repository、commit、workflow が作ったかを証明できない。

長期の署名秘密鍵を GitHub Secrets へ置くと、発行、ローテーション、失効、漏えい対応が必要になる。
GitHub Actions の OIDC identity と Sigstore を使えば、短命な証明書で workflow identity を成果物へ結び付けられる。

[ADR-017](./ADR-017-adopt-trunk-based-repository-governance.md) は Action の commit SHA 固定と最小権限を要求する。
[ADR-018](./ADR-018-automate-semantic-releases.md) は release-please の責務を版採番、CHANGELOG、tag、GitHub Release に限定し、成果物公開を別 workflow に分ける。

## Decision

バックエンドコンテナは専用の publish workflow で一度だけ build し、Amazon ECR へ push する。
後続処理と本番 CD はタグではなく、その push が返した immutable digest を受け渡す。

publish workflow は GitHub OIDC で ECR publish 専用 IAM role を引き受ける。
長期 AWS access key と署名秘密鍵は GitHub Secrets に保存しない。
IAM trust policy は、この repository、`container-publish.yml`、許可した `main` または release tag の ref に限定する。

イメージ署名には Sigstore Cosign の keyless signing を採用する。
Cosign は同じ ECR digest へ署名し、GitHub Actions の OIDC identity を証明書へ記録する。

SLSA provenance には GitHub Artifact Attestations を採用する。
`actions/attest-build-provenance` が同じ ECR digest を subject とする SLSA provenance を生成し、OCI registry へ保存する。
この構成だけを根拠に SLSA Build Level 3 を主張しない。
Level 3 を要求するときは、再利用可能な trusted builder workflow と呼び出し元からの隔離を別途設計する。

Docker BuildKit の SBOM 出力を有効にし、イメージ構成の SBOM を同じ公開処理へ含める。
既存の CycloneDX application SBOM は JVM 依存関係の検査用として維持し、イメージ SBOM と同一視しない。

publish workflow は手動実行から開始し、次を満たす ref だけを受け付ける。

- `main`
- `v` で始まる release tag

ECR、OIDC IAM role、repository lifecycle policy が IaC で確定するまで、自動 release trigger は追加しない。

本番 CD は deploy 前に次を fail-closed で検証する。

- ECR に入力 digest が存在する。
- Critical vulnerability が許容基準以下である。
- Cosign の署名 issuer が GitHub Actions である。
- 署名 identity がこの repository の `container-publish.yml` である。
- SLSA provenance の subject が入力 digest である。
- provenance の source repository と source commit が承認対象と一致する。

Action は検証済みの完全な commit SHA へ固定する。
Cosign の installer と Cosign 本体も版を固定する。
Dependabot の GitHub Actions ecosystem 更新で新しい版を受け取り、SHA と upstream tag の対応をレビューする。

## Consequences

### Positive

- ECR digest がこの repository の信頼した workflow と commit から生成されたことを検証できる。
- 署名用の長期秘密鍵を管理せずに済む。
- build、署名、provenance、deploy の対象 digest が一つに揃う。
- 本番 CD が未署名、別 repository、別 commit、別 digest の成果物を拒否できる。

### Negative

- GitHub Artifact Attestations と Sigstore の可用性へ公開処理が依存する。
- ECR の OCI referrer と lifecycle policy が、署名と attestation をイメージより先に削除しないよう管理する必要がある。
- private repository で Artifact Attestations を使う場合は、対応する GitHub plan が必要になる。
- publish 用 IAM role と本番 deploy 用 IAM role を別々に用意する必要がある。

### Neutral

- GitHub Artifact Attestations の署名済み provenance と Cosign のイメージ署名は目的が異なるため、両方を生成する。
- GitHub Release はリリースメタデータを管理し、ECR が実行可能成果物を保持する。
- AWS Signer または admission policy が必要になった場合は、この ADR を置き換えて trust root を変更する。

## Alternatives Considered

### 長期 Cosign key を GitHub Secrets に保存する

- **Description**：固定鍵でイメージを署名する。
- **Pros**：OIDC と public transparency log に依存せず署名できる。
- **Cons**：鍵の保管、ローテーション、失効、漏えい対応が増えるため採用しない。

### GitHub Artifact Attestations だけを使う

- **Description**：署名済み SLSA provenance を成果物の唯一の証明にする。
- **Pros**：生成物と検証方式が一つになる。
- **Cons**：既存または将来の `cosign verify` を使う registry policy が standalone image signature を要求する場合に対応できないため採用しない。

### AWS Signer を使う

- **Description**：AWS Signer と ECR の統合を trust root にする。
- **Pros**：AWS 内の権限と監査へ統合できる。
- **Cons**：現在は AWS infrastructure が未定であり、GitHub Actions の source workflow と commit を直接結び付ける構成より先に AWS 固有の運用が増えるため採用しない。

### release-please workflow でコンテナも公開する

- **Description**：tag と GitHub Release の作成ジョブへ ECR push を追加する。
- **Pros**：release event と成果物公開を一つの workflow にまとめられる。
- **Cons**：版採番の権限と AWS publish の権限が同じ trust boundary に入り、ADR-018 の責務分離に反するため採用しない。

## References

- [GitHub Docs: Artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations)
- [Sigstore: Keyless signing](https://docs.sigstore.dev/cosign/signing/overview/)
- [Sigstore: Verifying signatures](https://docs.sigstore.dev/cosign/verifying/verify/)
- [SLSA specification](https://slsa.dev/spec/v1.0/)
- [`production-cd.yml.example`](../../.github/workflows/production-cd.yml.example)
