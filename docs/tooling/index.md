# 開発ツール

- [Taskfileの書き方](taskfile.md)：Taskfile.ymlのTaskを追加、変更、削除するとき
- [開発ワークフロー](dev-workflow.md)：日常の開発で入口Taskをどの順で実行するか確認するとき
- [Lintとテストのリファレンス](lint-and-test.md)：検査Taskの仕様と、GitフックやCIでの実行の対応を確認するとき
- [compose-test の slot](test-slots.md)：`task test`、`task e2e`、`task e2e-up`、`task scan-dast`を複数のworktreeで流すとき、自分のslotのURLやポートを確かめるとき、slotの待ちや残ったコンテナを調べるとき
- [betterleaksの誤検知の除外](betterleaks-exclusions.md)：`scan-secrets`、`scan-secrets-all`、`lint-betterleaks`が誤検知で止めたとき、`.betterleaks.toml`や`.betterleaksignore`に除外を足すとき
- [Renovate](renovate.md)：Renovate の PR が出たとき、`renovate.jsonc` を変えるとき、GitHub App をインストールするとき
