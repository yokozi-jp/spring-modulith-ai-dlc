<!-- markdownlint-disable-file MD041 -->
<!--
本文の節の書き方は、このテンプレートを正とする。
タイトル、セルフコメント、チェックリストの決め方は docs/pull-request/pull-request-description.md に従う。
この本文は squash commit の本文になる（docs/repository/branch-protection.md）。
節は残し、コメントと該当しないチェックリスト項目は消してから出す。
-->

## Summary

<!--
変更の要点を、図、diff、ツリー、疑似コードのうち一番小さいもので示す。
文章は短くする。
分割した Pull Request の一部なら、その旨と残りの作業の置き場所も書く。
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
- **Review:** <!-- /thermos の指摘と対応。実行しなかったときは理由（例：docs の誤字だけ）を書く -->

## Merge Danger

**Door:** <!-- two-way（簡単に戻せる）/ one-way（戻しにくい）。one-way は、docs/pull-request/review-request-and-merge.md の「戻しにくい変更を含む」条件に当たる変更で、迷うときは one-way にする -->

**Blast Radius:** <!-- 影響範囲を短い句で。例：api のみ、web の表示、全体の CI -->

- [ ] 意図した API 破壊的変更では、`api-breaking-approved` ラベルを付け、本文に理由、影響、移行方法を書き、タイトルまたは squash commit の `!` か commit footer の `BREAKING CHANGE:` を記載した
- [ ] API 契約を変えた場合、OpenApiConfig の契約の版（info.version）を上げた（互換な追加は MINOR、破壊的変更は MAJOR）

<!-- 関連する Issue があれば書く。merge 時に自動で close される。 -->

Closes #
