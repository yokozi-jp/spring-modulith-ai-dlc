# バッチ

- [バッチのジョブの入口と起動](job-entry.md)：業務のバッチを Spring Batch の Job で作るとき、依存、JobRepository、起動のしかた、終了コード、JobParameters、トランザクション、テナントを決めるとき
- [バッチの定時起動と追いつき](scheduling-and-catch-up.md)：定時に起動する Job の起動時刻や期間の区切りを決めるとき、追いつきや 0 件の扱いを決めるとき、未起動や遅延の監視を作るとき
- [バッチの冪等性と再開](idempotency-and-restart.md)：Job の reader と writer を作るとき、再開、skip と retry、二重の起動で結果が変わらないかを確かめるとき
- [バッチとオンラインの排他](online-exclusion.md)：オンラインの時間帯に動く Job を作るとき、Job とオンラインが同じ表を書くかを確かめるとき
- [バッチのテスト観点](testing.md)：Job のテストを書くとき、Job のテストが足りているかを確かめるとき
