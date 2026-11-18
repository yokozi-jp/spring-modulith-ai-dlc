<!-- markdownlint-disable-file MD041 -->
<!--
本文の節の書き方は docs/pull-request/pull-request-description.md に従う。
この本文は squash commit の本文になるので、使わない節とコメントは消してから出す。
-->

## Summary

<!--
変更の要点を、図、diff、ツリー、疑似コードのうち一番小さいもので示す。
文章は短くする。
-->

## Evidence

<!--
動くことの証拠を Before と After で示す。
画面の変更は画像を貼り、操作の流れを示す必要があれば動画にする。
それ以外はテストやコマンドの出力を、検索できるように画像ではなくテキストで貼る。
長い記録は <details> で折りたたむ。
秘密情報、実資格情報、個人情報を含めない。ログに含めてはならない値は docs/observability/conventions.md に従う。
-->

- **Before:**
- **After:**

## Merge Danger

**Door:** <!-- two-way（簡単に戻せる）/ one-way（戻しにくい。データの移行、公開 API の変更、削除など） -->

**Blast Radius:** <!-- 影響範囲を一語で。例：api のみ、web の表示、全体の CI -->

<!-- API の破壊的変更と契約の版は、該当しない項目を消す。 -->

- [ ] 意図した API 破壊的変更では、`api-breaking-approved` ラベルを付け、本文に理由、影響、移行方法を書き、タイトルまたは squash commit の `!` か commit footer の `BREAKING CHANGE:` を記載した
- [ ] API 契約を変えた場合、OpenApiConfig の契約の版（info.version）を上げた（互換な追加は MINOR、破壊的変更は MAJOR）

<!-- 関連する Issue があれば書く。merge 時に自動で close される。 -->

Closes #
