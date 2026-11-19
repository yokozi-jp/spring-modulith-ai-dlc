---
type: Convention
title: betterleaksの誤検知の除外
description: betterleaksの誤検知を`.betterleaks.toml`と`.betterleaksignore`で除外する書き方と、除外してはならない場合を定める。`scan-secrets`、`scan-secrets-all`、`lint-betterleaks`が誤検知で止めたとき、除外を足すときに読む。
tags: [convention, tooling, security, betterleaks]
---

# betterleaksの誤検知の除外

除外は行単位で書き、本物の秘密情報は除外しない。
除外を足したら、`lint-betterleaks`で、そのファイルに置いたtokenが検出されることを確かめる。

- 除外は行単位にする。`.betterleaks.toml`の`paths`と`regexes`を併記するときは、`condition = "AND"`と`regexTarget`を必ず書く。
  既定の`condition`は`OR`で、`paths`に一致したファイルが丸ごと走査から外れる。
  既定の`regexTarget`は`secret`で値しか照合しない。
  キー名を含めて照合するときは`match`を選び、正規表現を`^`と`$`で固定する。`line`は一致した行の全規則の検出を消すため、同じ行の別のtokenも見逃す。
- 除外を足したら、`lint-betterleaks`で、そのファイルに置いたtokenが検出されることを確かめる。
- 履歴にだけ残る誤検知は、`.betterleaksignore`にfingerprint（`commit:file:rule:line`）を1行足して除外する。mainを取り込むmerge commitを含むPull Requestでは、同じ検出が別のcommitの指紋で再び出ることがある。
- betterleaksが止めたとき、本物の秘密情報なら除外しない。pushしていれば無効化（rotate）する。
