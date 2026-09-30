---
inclusion: fileMatch
fileMatchPattern: ["**/*.md"]
name: writing
description: 日本語の技術文書（docs、ADR、README、steering、記事、書籍原稿）を markdown で書く、または推敲するときに、どの文章規範を読むかを示す。
---

# 日本語の技術文書を書くとき

日本語の技術文書を書く、または推敲するときは、`docs/writing/japanese-tech-writing.md` を読んでから書く。
文章に関する文書の一覧は `docs/writing/index.md` にある。

## 行動指針

- 書き始める前に文章規範を読み、書き上げたら規範の各節で点検する。
- ADR を書くときは、書式と採番を `docs/adr/conventions.md` で確認する。

## 書き上げたあとの点検

- 一文ごとに改行し、段落の区切りは空行にする。
- 日本語の地の文と見出しでダッシュ（`—`、`――`）を使わない。
- 日本語の並列で中黒（`・`）を使わない。
- 「重要なのは」「正面から」「多角的」のような LLM っぽい空句を書かない。
