---
type: Convention
title: 到達性の監視
description: ESP を Amazon SES とする前提で、送ったメールのハードバウンス率と迷惑メールの報告率の目標とアラームの閾値、送らないアドレスを SES の抑制リストで止めること、受信側の監視の道具の登録を定める規約。本番でメールを送り始める前、メールの監視とアラームを設定するとき、バウンスや苦情が増えたときに読む。
tags: [convention, mail, monitoring, aws, future-arch-guidelines]
---

# 到達性の監視

ハードバウンス率は 1% 未満、迷惑メールの報告率は 0.1% 未満を目標にし、警告と重大の閾値でアラームを置く。
ハードバウンスと苦情を受けたアドレスへの送信は SES のアカウントの抑制リストで止め、配信を止めるアドレスの一覧をアプリに持たない。
Gmail の迷惑メールの報告は SES に届かないので、Google Postmaster Tools に送信ドメインを登録して毎週確かめる。

この規約は、ESP を Amazon SES とする前提で書いている（[送信ドメインの認証と評判](sending-domain.md#esp)）。

## 目標とアラームの閾値

| 指標               | 目標      | 警告           | 重大          | 重大のときの対応                                 |
| ------------------ | --------- | -------------- | ------------- | ------------------------------------------------ |
| ハードバウンス率   | 1% 未満   | 0.8% を超えた  | 1.0% を超えた | 送信を止め、宛先が無効になった原因を調べる       |
| 迷惑メールの報告率 | 0.1% 未満 | 0.08% を超えた | 0.1% を超えた | その種類のメールの送信を止め、件名と本文を見直す |

- 表の目標と閾値は、原文の推奨値を採ったこのリポジトリの選択であり、SES や Gmail が課す上限ではない。
- 警告では、送信を続けたまま原因を調べる。
- ソフトバウンス率には目標とアラームを置かない。
  SES がアカウントのバウンス率として数えるのはハードバウンスだけで、ソフトバウンスの率を出さないためである（[Amazon SES の Bounce FAQ の Q3](https://docs.aws.amazon.com/ses/latest/dg/faqs-enforcement.html#bn-q3)）。
- アラームは、SES が CloudWatch に出す `Reputation.BounceRate` と `Reputation.ComplaintRate` に置く（[Creating reputation monitoring alarms using CloudWatch](https://docs.aws.amazon.com/ses/latest/dg/reputationdashboard-cloudwatch-alarm.html)）。
- `Reputation.ComplaintRate` には Gmail の報告が入らないので、迷惑メールの報告率のアラームは Gmail 宛てのメールを見ていない。
  Gmail 宛ての報告率は、下の Postmaster Tools で確かめる。

## Gmail 宛ての迷惑メールの報告率

- Postmaster Tools の迷惑メール率を 0.1% 未満に保つ。
  [Gmail の送信者ガイドライン](https://support.google.com/a/answer/81126?hl=ja)は、0.10% 未満に保ち、0.30% 以上にならないよう求めている。
- 週に 1 度の確認で 0.1% 以上の日があったら、上の表の迷惑メールの報告率の重大と同じ対応をとる。
- Gmail 宛ての報告率は、アラームでは見つからず、週に 1 度の確認で見つかる。
  Postmaster Tools のデータは通常 24 時間以内に更新されるが、それより遅れることもある（[Postmaster Tools ダッシュボード](https://support.google.com/a/answer/14668346?hl=ja)）。
  このため、報告率が上がってから見つかるまで、通常は最大で 1 週間と 1 日ほどかかり、その間に 0.3% 以上になっても止められない。
- 送る量が少ない日は、ダッシュボードにデータが出ないことがある（同じ文書）。
  データが出ない週は、報告率を 0 とみなさず、確かめられなかったことを記録する。

## 送らないアドレス

- SES のアカウントの抑制リストを、バウンスと苦情の両方で有効にする。
  2019 年 11 月 25 日より後に使い始めたアカウントは、既定で両方が有効である（[Using the Amazon SES account-level suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html)）。
- 抑制リストに入るのは、ハードバウンスになったアドレスと苦情を受けたアドレスで、ソフトバウンスになったアドレスは入らない（同じ文書）。
  Gmail は苦情の情報を SES に渡さないので、Gmail で迷惑メールと報告した人のアドレスは抑制リストに入らない（同じ文書）。
- 配信を止めるアドレスの一覧をアプリの DB に持たない。
  抑制リストにあるアドレスへの送信は、SES が受け付けたうえで配送しない（同じ文書）。
  このため、抑制リストにあるアドレスへメールを送り直しても、`SendEmail` は成功を返し、メールは届かない。
  アプリが SES のイベントの通知を受けない間は、アプリは届かなかったことを知らない。
- ソフトバウンスが続くアドレスへの送信は止めない。
  SES の抑制リストはソフトバウンスを入れず、アプリは配信を止める一覧を持たないためである。

## 受信側の監視の道具

- Google Postmaster Tools に送信ドメインを登録し、Gmail での迷惑メールの報告率とドメインの評判を毎週確かめる（確かめる頻度は原文に従う）。
- 登録は、本番でメールを送り始める前に済ませる。
  登録を含む本番の構築のチェックリストは [#665](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/665) で置く。
- Microsoft SNDS には登録しない。
  SNDS は送信に使う IP の責任者が IP ごとに申し込む仕組みで、共有 IP の責任者は ESP であるためである。
  専用 IP に替えるときに、SNDS への登録を見直す。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文の「配信停止リスト」は、SES のアカウントの抑制リストに置き換えた。
- 原文は、ソフトバウンス率に警告 2.5%、重大 3.0% の閾値を置くが、この規約は置かない。
- 原文は、ソフトバウンスが累計 3 回になったアドレスを配信停止リストに入れるが、この規約は入れない。
- 原文は、重大のときに関係者へエスカレーションするが、この規約は連絡先を定めていない。
  連絡先と担当は、本番の構築のチェックリスト（[#665](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/665)）で決める。
- 原文は Microsoft SNDS への登録を必須とするが、この規約は登録しない。
