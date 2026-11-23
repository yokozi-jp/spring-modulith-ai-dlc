---
type: Convention
title: 送信ドメインの認証と評判
description: ESP を Amazon SES とする前提で、メールを送るドメインの SPF、DKIM、DMARC の設定、DMARC のレコードの置き場所とポリシーを上げる順序、Gmail の送信者ガイドラインへの準拠、取引のメールのサブドメインと送信の IP を定める規約。送信ドメインを用意するとき、DNS のメールの認証レコードを変えるとき、送るメールの量や種類を増やすときに読む。
tags: [convention, mail, dns, security, future-arch-guidelines]
---

# 送信ドメインの認証と評判

送信ドメインは SPF と DKIM を ESP（Amazon SES）が示す値で設定し、カスタムの MAIL FROM ドメインで DMARC のアライメントを通す。
DMARC のレコードは送信のサブドメインに置き、RFC 9989 に従って `p=none` から `quarantine`、`reject` へ 1 段ずつ上げ、`p` を上げるたびに `t=y` を付けた段を挟む。
Gmail の送信者ガイドラインには送る量によらず全部従い、取引のメールは組織のドメインのサブドメインから ESP の共有 IP で送る。

## ESP

- メールの規約は、ESP を Amazon SES とする前提で書く。
- SES を選んだ ADR はまだない。
  [#571](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/571) は SES を前提にして、送り方（SES v2 の API と、ローカルとテストの WireMock）だけを決める。
  ESP の選定を ADR に残す作業は、[#675](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/675) で扱う。
- 送信ドメインを用意する手順は、本番の構築のチェックリスト（[#665](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/665)）に置く。

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
- DMARC のレコードは、From のドメインである送信のサブドメイン（例：`_dmarc.mail.example.com`）に置き、下の手順はこのレコードで進める。
  受信側は From のドメインの `_dmarc` を最初に引くので、このレコードは From がそのサブドメインのメールにだけ当たる（RFC 9989 の Section 4.10.1）。
- 組織のドメイン（例：`example.com`）にも DMARC のレコードが要る（RFC 9989 の Section 5.1.4）。
  組織のドメインのレコードは、組織のドメインから送る全部のメールと、自分のレコードを持たないサブドメインのメールに当たる（`sp` がなければ `p` が当たる）。
  このため、組織のドメインのレコードは下の手順で変えず、組織のドメインの管理者と決める。
- 送信のサブドメインのポリシーは、次の順に 1 段ずつ上げ、各段で集約レポートを読み、正当なメールがアライメントに失敗していないことを確かめてから次へ進む。
  1. `p=none` にし、`rua` に集約レポートの送り先を置く。
  2. `p=quarantine; t=y` にする。
  3. `t=y` を外し、`p=quarantine` にする。
  4. `p=reject; t=y` にする。
  5. `t=y` を外し、`p=reject` にする。
- `t=y` は、受信側に `p` より 1 段下のポリシーを当てるよう求める（RFC 9989 の Section 4.7 の `t` タグ）。
  `p=quarantine; t=y` は `none` を、`p=reject; t=y` は `quarantine` を求めるので、4 の段でも認証に失敗したメールは受信側で隔離されうる。
- RFC 7489 は知らないタグを無視するよう定めるので（RFC 7489 の Section 6.3）、RFC 7489 のままの受信側は `t=y` を無視して `p` のポリシーをそのまま当てうる（仕様から推した見込みで、受信側の実際の挙動は確かめていない）。
  `t=y` を付けた段でも、`p` のポリシーが当たってよい状態にしてから上げる。

## Gmail の送信者ガイドライン

- 1 日に送る量によらず、[Gmail の送信者ガイドライン](https://support.google.com/a/answer/81126?hl=ja)の要件に全部従う。
  Gmail の送信者ガイドラインが 1 日に Gmail へ 5,000 通以上送る送信者だけに課す要件（SPF と DKIM の両方、DMARC とそのアライメント）も満たす。
  送る量が増えたときに設定を変えずに済み、Gmail 以外の受信側でも到達しやすくなるためである。
- TLS での送信、正引きと逆引きの DNS、RFC 5322 に合うメッセージの形式は、ESP が満たす。
- 迷惑メールの報告率の目標と監視は、[到達性の監視](deliverability-monitoring.md)に従う。

## サブドメインと送信の IP

- 取引のメールは、組織のドメインのサブドメイン（例：`mail.example.com`）を From のドメインにして送る。
  組織のドメインから別の送信元（社員のメールや別のサービス）も送るとき、組織のドメインの DMARC のポリシーを上げると、それらのメールにも当たる。
  送信のサブドメインに自分の DMARC のレコードを置くと、このサービスのメールのポリシーを、組織のドメインのほかのメールと別に上げられる。
- サブドメインは、送り始める前に決める。
  From のドメインを変えると、DKIM と DMARC のレコードと Postmaster Tools の登録をやり直すことになるためである。
- ESP の共有 IP から送り、専用 IP を使わない。
  共有 IP は送信の実績があり、ウォームアップなしに送れるためである。
- 送る量が月に 10 万通以上になる見込みになったら、専用 IP に替えるかを見直す（原文が専用 IP を勧める境目）。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文の DMARC の手順は RFC 7489 と `pct` タグを前提にしており、RFC 9989 の `t` タグに書き換えた。
- 原文は ESP を特定しない。この規約は Amazon SES を前提にしている。
- 原文は、月に 50 万通を超える、取引のメールとマーケティングのメールの両方を送る、複数のブランドやサービスを運営する、のいずれかに当たるときにサブドメインを分ける。
  この規約は、DMARC のポリシーを組織のドメインと別に上げるため、これらの条件によらずサブドメインから送る。
- 原文は、後から分けたサブドメインには送信の履歴がなくウォームアップが要るとする一方で、ドメインのための特別なウォームアップはほとんど要らないともする。
  この規約は、ウォームアップをサブドメインの理由にしない。
