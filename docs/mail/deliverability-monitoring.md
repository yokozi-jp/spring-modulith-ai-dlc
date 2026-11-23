---
type: Convention
title: 到達性の監視
description: 送ったメールのハードバウンス率と迷惑メールの報告率の目標とアラームの閾値、送らないアドレスを SES の抑制リストで止めること、受信側の監視の道具の登録を定める規約。本番でメールを送り始める前、メールの監視とアラームを設定するとき、バウンスや苦情が増えたときに読む。
tags: [convention, mail, monitoring, aws, future-arch-guidelines]
---

# 到達性の監視

ハードバウンス率は 1% 未満、迷惑メールの報告率は 0.1% 未満を目標にし、警告と重大の閾値でアラームを置く。
ハードバウンスと苦情を受けたアドレスへの送信は SES のアカウントの抑制リストで止め、配信を止めるアドレスの一覧をアプリに持たない。
Gmail の迷惑メールの報告は SES に届かないので、Google Postmaster Tools を本番の構築の手順で登録する。

## 目標とアラームの閾値

| 指標               | 目標      | 警告           | 重大          | 重大のときの対応                                 |
| ------------------ | --------- | -------------- | ------------- | ------------------------------------------------ |
| ハードバウンス率   | 1% 未満   | 0.8% を超えた  | 1.0% を超えた | 送信を止め、宛先が無効になった原因を調べる       |
| 迷惑メールの報告率 | 0.1% 未満 | 0.08% を超えた | 0.1% を超えた | その種類のメールの送信を止め、件名と本文を見直す |

- 警告では、送信を続けたまま原因を調べる。
- 迷惑メールの報告率は、一時的にも 0.3% を超えないようにする（Gmail の送信者ガイドラインの上限）。
- アラームは、SES が CloudWatch に出す `Reputation.BounceRate` と `Reputation.ComplaintRate` に置く（[Creating reputation monitoring alarms using CloudWatch](https://docs.aws.amazon.com/ses/latest/dg/reputationdashboard-cloudwatch-alarm.html)）。

## 送らないアドレス

- SES のアカウントの抑制リストを、バウンスと苦情の両方で有効にする。
  2019 年 11 月 25 日より後に使い始めたアカウントは、既定で両方が有効である（[Using the Amazon SES account-level suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html)）。
- 配信を止めるアドレスの一覧をアプリの DB に持たない。
  抑制リストにあるアドレスへの送信は、SES が受け付けたうえで配送しない。
- 抑制リストに入るのはハードバウンスだけで、ソフトバウンスは入らない。

## 受信側の監視の道具

- Google Postmaster Tools に送信ドメインを登録し、Gmail での迷惑メールの報告率とドメインの評判を確かめる。
  Gmail は迷惑メールの報告を SES に渡さないので、`Reputation.ComplaintRate` には Gmail の報告が入らない。
- Microsoft SNDS は、送信に使う IP の責任者が IP ごとに申し込む仕組みなので、ESP の共有 IP から送る間は登録しない。
- 登録は本番の構築の手順に入れ、メールを送り始める前に済ませる。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文の「配信停止リスト」は、SES のアカウントの抑制リストに置き換えた。
- [Outlook.com Smart Network Data Services](https://substrate.office.com/ip-domain-management-snds/snds)
