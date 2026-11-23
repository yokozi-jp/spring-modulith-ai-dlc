---
type: Convention
title: メールのテスト
description: メールを送る処理を、ローカルとテストでは SES v2 の API の接続先を WireMock に向けて確かめ、本番の SES は手で疎通を確かめるだけにする規約。メールを送る処理のテストを書くとき、E2E で送ったメールの中身を使うときに読む。
tags: [convention, mail, testing, wiremock, future-arch-guidelines]
---

# メールのテスト

ローカルとテストでは、SES v2 の API の接続先を WireMock に向け、送ったメールを WireMock の要求の記録で確かめる。
本物の宛先へ送らずに、送ったことと宛先、件名、本文を確かめる。
本番の SES は手で疎通を確かめるだけにする。

## ローカルとテスト

- メールは、本番と同じ SES v2 の API の Client で送り、ローカル、結合テスト、E2E では接続先だけを WireMock に向ける。
  外部システムを WireMock で偽る形は [ADR-072](../adr/ADR-072-fake-external-systems-with-wiremock.md) に従う。
- 送ったメールの宛先、件名、本文は、WireMock の要求の記録から読んで確かめる。
- テストから本物の宛先へメールを送らない。

## 本番

- 本番の SES は、手で疎通を確かめるだけにし、自動のテストから送らない。
  WireMock は SES が要求を断る条件（宛先の形式、サンドボックスの宛先の確認）を再現しないので、その確認は本番の疎通の確認で補う。

## 送る土台と読む手順

- メールを送る Client、WireMock のスタブ、送ったメールを WireMock から読む手順は、[#571](https://github.com/yokozi-jp/spring-modulith-ai-dlc/issues/571) で置く。

## 出典

- フューチャー株式会社「メール設計ガイドライン」（[アーキテクチャ設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、commit `e309a6d`）、[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/deed.ja)
- このリポジトリの規約に合わせて抜粋、再構成、改変している。取り込みの方針は [ADR-040](../adr/ADR-040-import-future-architecture-guidelines.md) に従う。
- 原文はローカルのテスト用のメールサーバー（MailHog、MailSlurper）を挙げるが、本番と同じ API の経路を通すため WireMock に置き換えた。
