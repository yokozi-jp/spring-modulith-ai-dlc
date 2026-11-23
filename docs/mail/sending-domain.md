---
type: Convention
title: 送信ドメインの認証と評判
description: メールを送るドメインの SPF、DKIM、DMARC の設定、DMARC のポリシーを上げる順序、Gmail の送信者ガイドラインへの準拠、取引のメールのサブドメインと送信の IP を定める規約。送信ドメインを用意するとき、DNS のメールの認証レコードを変えるとき、送るメールの量や種類を増やすときに読む。
tags: [convention, mail, dns, security, future-arch-guidelines]
---

# 送信ドメインの認証と評判

送信ドメインは SPF と DKIM を ESP（Amazon SES）が示す値で設定し、カスタムの MAIL FROM ドメインで DMARC のアライメントを通す。
DMARC は RFC 9989 に従い、`p=none` から `quarantine`、`reject` へ 1 段ずつ上げ、上げる間は `t=y` を付ける。
Gmail の送信者ガイドラインには送る量によらず全部従い、取引のメールのサブドメインは送り始める前に決めて ESP の共有 IP から送る。

## SPF と DKIM

- SPF と DKIM のレコードは、ESP が示す値をそのまま DNS に設定する。
  ESP は送信に使う IP アドレスと署名の鍵を管理しており、利用者が値を組み立てると食い違うためである。
- DKIM の署名は From のドメインで行い、DKIM のアライメントを通す。
- From のドメインのサブドメインを、カスタムの MAIL FROM ドメインとして ESP に設定し、SPF のアライメントも通す。
  ESP の既定の MAIL FROM ドメインのままだと、SPF は ESP のドメインで認証され、From のドメインとそろわないためである。

## DMARC

- DMARC のレコードは [RFC 9989](https://www.rfc-editor.org/info/rfc9989) に従って書く。
  RFC 9989 は RFC 7489 を廃止し、`pct` タグを削除したので、`pct` を使わない。
- DMARC のレコードは、SPF と DKIM が両方とも認証を通ることを確かめてから置く。
- ポリシーは次の順に 1 段ずつ上げ、各段で集約レポートを読み、正当なメールがアライメントに失敗していないことを確かめてから次へ進む。
  1. `p=none` にし、`rua` に集約レポートの送り先を置く。
  2. `p=quarantine; t=y` にする。`t=y` は、受信側にポリシーを適用せず試験中として扱うよう求める。
  3. `t=y` を外し、`p=quarantine` にする。
  4. `p=reject; t=y` にする。
  5. `t=y` を外し、`p=reject` にする。

## Gmail の送信者ガイドライン

- 1 日に送る量によらず、[Gmail の送信者ガイドライン](https://support.google.com/a/answer/81126?hl=ja)の要件に全部従う。
  1 日 5,000 通以上の送信者だけに課される要件（SPF と DKIM の両方、DMARC とそのアライメント）も満たす。
  送る量が増えたときに設定を変えずに済み、Gmail 以外の受信側でも到達しやすくなるためである。
- TLS での送信、正引きと逆引きの DNS、RFC 5322 に合うメッセージの形式は、ESP が満たす。
- 迷惑メールの報告率の目標と監視は、[到達性の監視](deliverability-monitoring.md)に従う。

## サブドメインと送信の IP

- 取引のメールを送るサブドメイン（例：`mail.example.com`）を、送り始める前に決め、そこから送る。
  後からサブドメインを分けると、新しいサブドメインには送信の履歴がなく、評判を一から作るウォームアップが要るためである。
- ESP の共有 IP から送り、専用 IP を使わない。
  月に 10 万通未満の送信なら、送信の実績がある共有 IP でウォームアップなしに送れるためである。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文の DMARC の手順は RFC 7489 と `pct` タグを前提にしており、RFC 9989 の `t` タグに書き換えた。
