---
inclusion: fileMatch
fileMatchPattern: ["infrastructure/**", ".github/workflows/production-cd.yml.example"]
name: aws
description: infrastructure/ の AWS 構成や、AWS へデプロイするワークフローを追加や変更するときに使う。AWS のアカウント、セキュリティ、公開経路、実行基盤、監視、CI/CD、コストの規約の入口を示す。
---

# AWS の入口

詳細は `docs/aws/index.md` から必要な文書だけ読む。

## 行動指針

- 公開経路を決める前に、同一オリジンの決定（`docs/adr/ADR-014-use-same-origin-spa-security-boundary.md`）を確認する。
- 戻しにくい構成の選択（IaC のツール、ECS の起動タイプ、本番のログ保存先など）は ADR を起こしてから実装する。
