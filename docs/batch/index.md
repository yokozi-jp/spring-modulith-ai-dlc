# バッチ

- [バッチのジョブの入口と起動](job-entry.md)：業務のバッチを Spring Batch の Job で作るとき、依存、JobRepository、起動の Runner、終了コード、JobParameters と入力の検査を決めるとき
- [バッチのトランザクション、ロール、テナント](transaction-and-tenant.md)：ItemWriter と Tasklet から CommandHandler を呼ぶとき、Step のトランザクション、スパン、テナントごとの partition、並列を決めるとき
- [バッチの定時起動と追いつき](scheduling-and-catch-up.md)：定時に起動する Job の起動時刻や期間の区切りを決めるとき、期間の後に届く行や 0 件の扱いを決めるとき
- [バッチの冪等性と再開](idempotency-and-restart.md)：Job の reader と writer を作るとき、retry と失敗、二重の起動、止まった期間の直し方を確かめるとき
- [バッチとオンラインの排他](online-exclusion.md)：オンラインの時間帯に動く Job を作るとき、Job とオンラインが同じ表を書くかを確かめるとき
- [バッチのテスト観点](testing.md)：Job のテストを書くとき、Job のテストが足りているかを確かめるとき
