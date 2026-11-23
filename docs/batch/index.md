# バッチ

- [バッチのジョブの入口と起動](job-entry.md)：業務のバッチを Spring Batch の Job で作るとき、依存、起動の Runner、終了コード、二重の起動、JobParameters と識別の引数と入力の検査を決めるとき
- [バッチの JobRepository](job-repository.md)：Spring Batch のメタデータの表を作るとき、表の権限を変えるとき、Job の実行の記録やその時刻を読むとき
- [バッチのトランザクション、ロール、テナント](transaction-and-tenant.md)：ItemWriter と Tasklet から CommandHandler を呼ぶとき、Step のトランザクション、スパン、テナントごとの partition、並列を決めるとき
- [バッチの定時起動と追いつき](scheduling-and-catch-up.md)：定時に起動する Job の起動時刻や期間の区切りを決めるとき、期間の後に届く行や 0 件の扱いを決めるとき
- [バッチの冪等性と再開](idempotency-and-restart.md)：Job の reader と writer を作るとき、再開しても結果が 1 回だけ流したときと同じになるか、retry と失敗の扱いを確かめるとき
- [止まった期間を直す](runbook-stuck-period.md)：Job の起動が終了コード 1 か 5 で続けて終わり、追いつきが同じ期間で止まっているとき、期間を Job で流さないと決めたとき
- [バッチとオンラインの排他](online-exclusion.md)：オンラインの時間帯に動く Job を作るとき、Job とオンラインが同じ表を書くかを確かめるとき
- [バッチのテスト観点](testing.md)：Job のテストを書くとき、Job のテストが足りているかを確かめるとき
