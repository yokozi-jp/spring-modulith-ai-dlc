# AWS

- [AWSアカウントの分離と構成](account-structure.md)：AWS のアカウントと Organizations を設計するとき、デプロイ環境や共通機能のアカウントを追加するとき
- [全アカウントで有効にするAWSのセキュリティサービス](security-services.md)：AWS アカウントを作るとき、セキュリティサービスの設定や CloudTrail の保持期間を決めるとき
- [セキュリティの検知結果の分類と対応](security-findings.md)：セキュリティ基準を有効にしたとき、検知結果の対応期限を決めるとき、検知の通知を設定するとき
- [情報の機密性の分類と取り扱い要件](data-classification.md)：データを保存するリソースを設計するとき、暗号化、保持期間、削除の方法を決めるとき、監査への対応を設計するとき
- [内部リソースへの運用者の接続](operator-access.md)：踏み台の構成を決めるとき、DB のデータ調査やデータパッチの接続経路を用意するとき
- [AWSでのリクエストの入口とサービス間の経路](request-routing.md)：SPA と API の公開経路を設計するとき、API Gateway やロードバランサーを導入するか決めるとき、サービス間の呼び出しを追加するとき
- [AWSの実行基盤とジョブキューの選択](compute-selection.md)：Web API やバッチの実行基盤を構築するとき、Fargate のタスクを設定するとき、外部のメッセージブローカーを選ぶとき
- [CloudWatch Logsのロググループ](cloudwatch-logs.md)：CloudWatch Logs のロググループを作るとき、ログの保存先や保持期間を設定するとき
- [AWSのサービスのメトリクス監視](metrics-monitoring.md)：AWS のサービスの監視とアラートを設定するとき、どのメトリクスを監視するか迷ったとき
- [AWSへのCI/CDの構成](ci-cd.md)：AWS へのデプロイのワークフローを作るとき、CI に組み込むテストを決めるとき
- [AWSのコストの可視化と削減](cost-optimization.md)：AWS の構成を決めるとき、コストの按分や割引の購入を検討するとき、S3 のストレージクラスを決めるとき
