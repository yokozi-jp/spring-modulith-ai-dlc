# データベース

- [DBマイグレーション規約](migrations.md)：changesetやスキーマタグを追加または変更するとき、マイグレーションの実行経路やCI検証を確認または変更するとき
- [DB操作コマンドのリファレンス](commands.md)：DBを操作するTaskとGradleタスクの動作、影響範囲、必要な確認値を調べるとき
- [jOOQコード生成物の管理](jooq-codegen.md)：changeset追加後にjOOQコードを生成するとき、生成物の管理方針を確認するとき
- [DB接続情報とロール分離](connections.md)：接続用の環境変数やDBロールを追加、変更するとき、ステージングや本番のDBを準備するとき
- [DBのデプロイと切り戻し](runbook-deploy-and-rollback.md)：本番マイグレーションのパイプラインを組むとき、DBを切り戻すとき
