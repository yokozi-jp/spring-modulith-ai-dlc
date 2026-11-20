# メールと SMS の設計の docs（#323 の調査）

この文書は #323 の調査の記録で、merge しない。
結論は #323 のコメントと同じで、作業は #571 と #572 で行う。
向こうは `yokozi-jp/agent-software-factory`（`origin/main`、commit `29ba897`）、こちらは `yokozi-jp/spring-modulith-ai-dlc`（main、commit `63e5c9f`）である。

## 結論

一部採用とする。

- 招待のメールは、Spring の `JavaMailSender` から SMTP で送る。本番は Amazon SES の SMTP インタフェース、ローカルとテストは Mailpit にする（#571）。
- 向こうの `docs/mail/` のうち、送るメールの形と宛先、送信ドメインの認証と評判、到達性の監視、メールアドレスの扱い、メールのテストを、ADR-040 の方式で書き直して `docs/mail/` に置く（#572）。
- ADR-040 はメールと SMS を取り込まない原文に挙げている（ADR-040:39）ので、#572 で ADR-040 を改める。SMS は取り込まないまま残す。

HTML メール、配信結果の Webhook と送信の監査の表、メールアドレスの暗号化した列、エイリアスの正規化、マーケティングメールの規約、SMS と電話番号の文書、向こうの通知のワーカー（`docs/tooling/local-worker.md`）は取り込まない。

## 前提が変わったこと

ADR-040 がメールを除いた理由は、「このリポジトリで使うと決めていない技術を前提にする」ことだった（ADR-040:31）。
その後、次が決まった。

- #369（テナントの仕様）と #380 で、テナントの管理者が利用者をメールで招待する。#369 は「招待のメールの送り方は、#323 の結果に合わせる」としている。
- 本番は AWS の ECS on Fargate（ADR-041）、IdP は Cognito（ADR-102）である。

メールを送ることが決まったので、ADR-040 の理由はもう当てはまらない。
SMS は、電話番号を集める機能も SMS を送る機能もないので、理由が残る。

## #380 に要るもの

Issue #380 の受け入れ条件の「招待のメールの送り方は #323 の結果に合わせる」は、次のとおりにする。

- **送る経路**：招待を作る CommandHandler は招待の作成のイベントを発行し、`tenancy` の Listener が受けてメールを送る CommandHandler を呼ぶ。
  メールを送る Client は `tenancy.infrastructure.client` に置き、#571 の `JavaMailSender` を使う。
  外部システムを呼ぶ CommandHandler を Listener からだけ呼ぶ規則（#543）と、Client の役割（ADR-050）に合う。
- **イベントに載せる値**：イベントには招待の `public_id` だけを載せ、Listener がメールアドレスを DB から読む。
  イベントは出版のレジストリと archive に残るので、個人情報を載せない（`docs/integration/async-message-security.md:26-27`）。
  向こうは宛先のアドレスをメッセージに載せている（向こうの `packages/approval/events/src/index.ts:39` の `to: z.email()`）が、こちらの規約に合わない。
- **重複**：SMTP の送信には冪等キーがないので、届け方は少なくとも 1 回になる。送ってから commit の前に落ちると、再投入で 2 通目が出る。
  招待に送った時刻を記録し、Listener は記録があれば送らない。
  これで #551 の「同じイベントを 2 回届けて、2 回目が行を変えない」を満たす。
  向こうも同じ理由で 2 通を認めている（向こうの ADR 0041:22、`packages/approval/src/application/handle-notification.ts` の Javadoc）。
- **失敗**：一時障害（接続の失敗、タイムアウト、SMTP の 4xx）は例外を投げて再投入を待ち、宛先を断る SMTP の 5xx は招待に記録して正常に終える（ADR-072 の 1 と 4 の分け方）。
  ログには例外の型と SMTP の応答コードだけを出し、例外の文面を出さない（ADR-015）。向こうも同じ理由で文面を捨てている（向こうの `packages/approval/src/application/mail-sender.ts:19-29`）。
- **本文**：プレーンテキストにし、宛先は TO の 1 人にする。本文には招待を受け入れる画面へのリンクと期限を書き、トークンや秘密を書かない。
  リンクに載せるのは招待の `public_id`（UUID v4）で、受け入れは確認済みのメールアドレスとの一致で判定するので、リンクを知るだけでは受け入れられない（#369）。
- **一致の判定**：招待のアドレスと ID トークンの `email` は、大文字と小文字を区別せずに比べ、`+` やドットの正規化をしない（下の「取り込まないもの」のエイリアスの正規化）。
  形式の検査は、バックエンドは `@Email`、フロントエンドは `z.email()` にし、正規表現を自作しない。
- **テスト**：結合テストは Mailpit の HTTP の API で届いたメールを読む。失敗は Mailpit の Chaos で SMTP の 451 と 550 を返させて確かめる。
  #369 は「招待の流れはメールを伴い、#323 の結果を待つので、E2E にしない」としている。Mailpit で E2E からもメールを読めるので、この理由はなくなる。E2E に足すかは #380 で決める。
- **文面の言語**：Listener の中には要求の `Accept-Language` がない。ADR-016 の既定の日本語にするか、招待を作った要求のロケールを招待に持つかを #380 で決める。

## 同じ決定になっているもの

- **アプリの利用者を `sub` で引き、メールアドレスで紐付けない**：向こうの `federated-login.md:43-46` は、ソーシャルログインの紐付けを ID トークンの `sub` で区別する。
  こちらは ID 連携の表で `(iss, sub)` を利用者に対応づける（#369 の「テナントと所属」、ADR-101）。
- **メールアドレスの正を IdP に置く**：向こうの `email-table-design.md:20-26` は、認証を IDaaS に任せ、アプリはアドレスをキャッシュとしてだけ持つ。
  こちらも IdP は認証だけを行い、`email_verified` を ID トークンから読む（ADR-101、ADR-102:55）。
- **メールアドレスの到達の確認を、確認のメールで行う**：向こうの `email-validity.md:121-123` と `confirmation-method.md:29-33` は、確認のメールのリンクで到達と所有を確かめる。
  こちらは、確認を IdP の `email_verified` に任せ、招待のリンクは期限つきで 1 回だけ受け入れる（#369）。
- **自前の送信サーバーを持たず ESP を使う**：向こうの `service-selection.md:24-25`、`:31-45`。こちらも SES を使う（#571）。
- **ローカルとテストを Mailpit にし、ESP の偽物を作らない**：向こうは結合テストを testcontainers の Mailpit で回し、SES の偽物を作らない（向こうの `apps/worker/src/env.ts:18`、`docs/tooling/local-worker.md:11`）。
  こちらは外部システムの偽物を本番のコードに置かない（ADR-072:31-34）。
  イメージの版を compose のファイルに持ち、テストがそこから読む形（向こうの `local-worker.md:70`）も、こちらの ADR-085 と同じである。
- **ログとイベントにメールアドレスを載せない**：向こうの `security.md:36-39` の「アクセスを限定する」は、こちらの ADR-015:33 と `async-message-security.md:26-27` が持つ。

## 取り込むもの

- **メールを `JavaMailSender` の SMTP で送る**（#571）：Spring Boot は `spring.mail.host` があれば `JavaMailSender` を自動構成する（[Sending Email](https://docs.spring.io/spring-boot/reference/io/email.html)）。
  本番もローカルも同じ SMTP の経路にし、送る先だけを変える。ADR-072 の「本番のコードは実際の Client だけを持ち、接続先を偽物に向ける」と同じ形になる。
  JavaMail の既定のタイムアウトは無限なので（同じ文書）、ADR-019 の値（接続 1 秒、呼び出し全体 2 秒、ADR-019:42-43）で設定する。
  向こうは本番を SES v2 の `SendEmail`、ローカルを SMTP にして、本番の経路を検証の環境で確かめている（向こうの `apps/worker/src/runtime.ts:15-20`、`apps/worker/src/mail/ses.ts:10-11`）。
- **Mailpit をローカルとテストに置き、Keycloak のメールも受ける**（#571）：こちらの realm は `resetPasswordAllowed` が true なのに SMTP がない（`docker/keycloak/realm.json:5-6`）。Mailpit に向けると、パスワードの再設定のメールも届く。
- **送るメールの形と宛先**（#572）：TO の 1 人（`to-cc-bcc.md:41-46`）、本文に秘密を書かずリンクにする（`security.md:26`）、添付をせず期限のあるリンクにする（`file-attachment.md:39-48`）、取引の連絡に広告を混ぜない（`legal-compliance.md:78-80`）、From の表示名と From のドメイン（`sender-address.md:35-50`）。
  どれも短い規則で、招待のメールの実装でそのまま使う。
- **送信ドメインの認証と評判**（#572）：SPF と DKIM を ESP の値で設定し（`domain-authentication.md:74-77`、`:128-130`）、カスタムの MAIL FROM で DMARC のアライメントを通す（`custom-domain.md:20-24`）。
  DMARC は RFC 9989 で 1 段ずつ上げる（`dmarc.md:99-108`）。向こうは原文の RFC 7489 と `pct` を、RFC 9989（7489 と 9091 を obsolete にした）に書き換えている（向こうの `about.md:32`）。[RFC 9989](https://www.rfc-editor.org/info/rfc9989) が 7489 と 9091 を obsolete にしたことを確かめた。
  Gmail の送信者ガイドラインに送る量によらず従う（`gmail-sender-guidelines.md:33-51`）。
  取引のメール用のサブドメインは、送り始める前に決める（`reputation.md:157-167`）。後から分けると、新しいサブドメインにウォームアップが要るためである。
- **到達性の監視**（#572）：ハードバウンス率と迷惑メールの報告率の目標と閾値（`monitoring.md:22-41`）を取り込む。
  原文の配信停止リスト（`list-cleansing.md:37`、`:61-66`）は自前で持たず、SES のアカウントの抑制リストを使う。
  2019 年 11 月 25 日より後に使い始めたアカウントは、既定でバウンスと苦情の両方を抑制する（[Using the Amazon SES account-level suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html)）。
- **メールアドレスの扱い**（#572）：形式の検査を寛容にし（`email-validity.md:58-67`）、フレームワークの検査を使う。
  Zod 4.6.5 の `z.email()` の既定の正規表現は、ドットの連続を断る（zod の `v4/core/regexes.js:34`）。取引先の業務のアドレスが相手なので、これで足りる（判断）。
- **メールのテスト**（#572）：Mailpit と HTTP の API で確かめ、本番は手での疎通の確認にとどめる（`testing.md:24-28`、`:34`）。
  原文は MailHog と MailSlurper を挙げるが、向こうと同じく Mailpit にする。

## 取り込まないもの

- **HTML メールとマルチパート**（`email-representation.md:40-44`、`:66-83`）：送るのは招待のリンクだけで、HTML の表現力が要らない。
  原文も、テンプレートごとに主要なクライアントで表示の崩れを送る前に確かめることを求めており（`:83`）、その費用に見合わない。
  向こうも実装はプレーンテキストだけである（向こうの `packages/approval/src/application/mail-sender.ts:3-8`）。
- **配信結果の Webhook と送信の監査の表**（`audit.md:31-41`、`:67-70`）：ESP の Webhook をキューで受けて、送信ごとの行の状態を更新する形である。
  受ける入口、SNS とキュー、表とその保持期限が増える。
  こちらが送るのは招待だけで、送ったことは招待の行の送った時刻で分かる。バウンスした宛先は SES の抑制リストが止める。
  紛争や監督官庁への説明のために送信の証明が要るメール（利用規約の変更、督促）はまだない。
- **メールアドレスを暗号化した列**（`security.md:36`）：招待の受け入れでアドレスの一致を比べるので、列を暗号化すると索引で引けない。
  保存の暗号化は RDS のストレージの暗号化で、アクセスの限定はモジュールの DB ロール（ADR-094）で行う（判断）。
- **エイリアスの正規化**（`email-alias.md:66-80`）：原文の推奨は、B2C の無料トライアルの重複登録を防ぐためのものである。
  こちらの招待は取引先の業務のアドレスが相手で、独自ドメインでは `+` 以降が別の宛先でありうる。正規化すると、別の人のアドレス宛ての招待を受け入れられる。
- **ソーシャルログインの紐付けの画面とログインのヒント**（`federated-login.md:47-63`）：こちらは顧客の IdP との連携を扱わない（#369 の Out of Scope）。
- **ESP の製品の比較、IP プールと専用 IP、ウォームアップの手順**（`service-selection.md:18-29`、`reputation.md:56-100`）：本番は SES と決まっており、招待の量では ESP の共有 IP で足りる（`reputation.md:60` の月間 10 万通未満）。
- **マーケティングメール、送るタイミング、開封のトラッキング、`List-Unsubscribe`**（`send-timing.md:42-61`、`open-tracking.md`、`gmail-sender-guidelines.md:49-51`）：こちらは広告のメールを送らない。
  送るタイミング（`send-timing.md:20-40`）は、機能ごとに仕様で決める。
- **Reply-To と返信の窓口**（`sender-address.md:75-79`、`:104-113`）：問い合わせの窓口がまだない。From の表示名とドメインだけを取り込む。
- **SES のテナントの機能**：SES は送るときに API か SMTP のヘッダーでテナントを指定し、テナントごとに評判と抑制リストを分けられる（[Amazon SES tenants](https://docs.aws.amazon.com/ses/latest/dg/tenants.html)）。
  送るのは管理者の招待だけで量が少なく、テナントを作るたびに SES の資源を作る運用が見合わない。
  SMTP のヘッダーでも指定できるので、#571 の SMTP の経路はこの機能を使う妨げにならない。
- **SMS と電話番号の文書**（`sms-vs-email.md`、`sms-cost-and-service.md`、`phone-number.md`、`phone-verification.md`）：こちらは電話番号を集めず、SMS を送る機能がない。
  向こうの文書も「SMS の利用をなるべく下げる」（`sms-vs-email.md:52-53`）を推奨の先頭に置いている。
- **向こうの通知のワーカーと ElasticMQ**（`docs/tooling/local-worker.md`）：向こうはメールを SQS のキューから読む別のプロセスで送る（ADR 0013、ADR 0041）。
  こちらはキューを置かず、Spring Modulith のイベント出版のレジストリと再投入で送る（#311、#313）。Mailpit の部分だけを取り込む。
- **原文の出典節と免責をそのまま置く形**（向こうの `about.md`）：こちらは ADR-040 の方式で書き直し、各文書の末尾に出典節を置く。
- **メールを送る機能の回数の上限を新しく作る**（`security.md:28`）：招待はテナントの管理者だけが作れる。
  回数の上限が要るときの置き場所は `docs/web-api/edge-responsibilities.md:36-39` がすでに決めているので、#572 でそこへリンクする。

## ほかの Issue との関係

- **#380（招待）**：上の「#380 に要るもの」のとおりにする。#380 は #571 を待つ。
- **#558（バッチ）**：ADR-040:39 の同じ行を直す。後に merge する側が両方の変更を残す。
- **#543（外部システムを呼ぶ CommandHandler を Listener からだけ呼ぶ）**：メールを送る Client は外部システムの Client なので、この規則の対象になる。招待のメールを Listener から送る形は、この規則に合う。
- **#551（同じイベントを 2 回届ける）**：招待に送った時刻を記録して 2 回目を送らない形で満たす。
- **#562（Spring Modulith 2.2、`AbandonPolicy`）**：宛先を断る SMTP の 5xx を、業務の状態に記録して正常に終えるか、すぐ諦める失敗として `ABANDONED` にするかは、#562 の方針に合わせる。
- **#316、#369、ADR-101、ADR-102**：IdP は認証だけを行う。Cognito はメールアドレスの確認のメールを自分で送るので、そのメールは #571 の経路を通らない。

## 限界

- SES の SMTP インタフェースは、送っていない。資格情報の作り方と STARTTLS は AWS の文書で確かめただけである。
- Spring Cloud AWS 4.2.2 は、README の対応表で Spring Boot 4.0.x を挙げており、4.1.x を挙げていない。こちらの 4.1.2 で動くかは試していない。
- Mailpit の Chaos は、1 つの Mailpit の全部の送信に効く。同じ JVM の中でテストを並列に流すと、失敗のテストが別のテストの送信を断りうる。#571 で、失敗のテストの並列の扱いを決める。
- `email_verified` が Keycloak と Cognito の ID トークンに入るかは、#380 が確かめる（ADR-102:55）。
- 招待された人が IdP にまだアカウントを持たないときの登録の経路を、どの Issue も決めていない。
  ローカルの Keycloak は `registrationAllowed` が false で（`docker/keycloak/realm.json:5`）、新しい人は自分で登録できない。Cognito の自己登録を使うかも決まっていない。
- 文面の言語は決めていない（上の「#380 に要るもの」）。

## 確かめたこと

向こうのリポジトリ（commit `29ba897`）で、次を確かめた。

- `docs/mail/` は 28 の文書（`index.md` を入れて 29）で、`about.md:14-33` が出典（フューチャー株式会社のメール設計ガイドラインと SMS 設計ガイドライン、CC BY 4.0）と変更点を書く。
- `docs/mail/` を参照するのは `docs/index.md:15` だけで、コードの Javadoc は ADR を参照する。
- 送信の実装は `apps/worker/src/mail/ses.ts`（SES v2 の `SendEmailCommand`、本文は `Text` だけ）と `apps/worker/src/mail/smtp.ts`（nodemailer で TLS なしの SMTP）で、`runtime.ts:15-20` が環境で選ぶ。
- 通知のメッセージは宛先のアドレスを持つ（`packages/approval/events/src/index.ts:39`）。

こちらのリポジトリ（main、commit `63e5c9f`）で、次を確かめた。

- `backend/build.gradle`、`application.yaml`、`docker/compose.yml`、`docker/compose-test.yml` に、メールの依存、設定、コンテナがない。
- `docs/` にメールの規約がない。ADR の一覧（`docs/adr/index.md`）にメールの ADR がない。
- `docker/keycloak/realm.json:5-6` は `registrationAllowed: false`、`resetPasswordAllowed: true` で、`smtpServer` がない。
- ヘルスチェックは `readiness` だけを見る（`docker/compose.yml:223`、`docker/compose-test.yml:139`、`backend/Dockerfile:83`）。Spring Boot の mail の health indicator が増えても、readiness の group には入らない。

一次資料で、次を確かめた。

- `spring-boot-starter-mail` と `spring-boot-mail` に 4.1.2 があり、最新は 4.2.0 である（Maven Central）。Spring Boot 4.2 のリリースノートに、メールの変更はない。
- Spring Cloud AWS 4.2.2 の `SimpleEmailServiceJavaMailSender` は SES v1 の `SendRawEmail` を呼ぶ（`spring-cloud-aws-ses/src/main/java/io/awspring/cloud/ses/SimpleEmailServiceJavaMailSender.java:44-47`、`:216-217`）。
- SES の SMTP の資格情報は、一時的な資格情報から作れない（[Obtaining Amazon SES SMTP credentials](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html)）。
- Mailpit の最新は v1.34.0（2026-11-17）で、SMTP を落とせた脆弱性 GHSA-v887-64wg-qqc9 を直している。Chaos は v1.20.0 からある。
- フューチャー株式会社のメール設計ガイドラインは、ADR-040 が固定した commit `e309a6d` に `documents/forMail/mail_guidelines.md` と `sms_guidelines.md` としてある。

## 出典

- 向こう：`docs/mail/`（`index.md`、`about.md`、`security.md`、`audit.md`、`testing.md`、`email-table-design.md`、`email-validity.md`、`email-alias.md`、`confirmation-method.md`、`federated-login.md`、`sender-address.md`、`send-timing.md`、`email-representation.md`、`to-cc-bcc.md`、`file-attachment.md`、`domain-authentication.md`、`custom-domain.md`、`gmail-sender-guidelines.md`、`dmarc.md`、`reputation.md`、`list-cleansing.md`、`monitoring.md`、`legal-compliance.md`、`open-tracking.md`、`troubleshooting.md`、`sms-vs-email.md`、`sms-cost-and-service.md`、`phone-number.md`、`phone-verification.md`）、`docs/tooling/local-worker.md`、`docs/adr/0041-standard-queue-per-task-with-dlq.md`、`apps/worker/src/`、`packages/approval/src/application/`、`packages/approval/events/src/index.ts`
- こちら：ADR-008、ADR-015、ADR-016、ADR-019、ADR-040、ADR-041、ADR-045、ADR-050、ADR-072、ADR-085、ADR-094、ADR-101、ADR-102、`docs/integration/async-message-security.md`、`docs/web-api/edge-responsibilities.md`、`docs/frontend/form-validation.md`、`docker/keycloak/realm.json`、#369、#380
- [メール設計ガイドライン](https://future-architect.github.io/arch-guidelines/documents/forMail/mail_guidelines.html)、[future-architect/arch-guidelines](https://github.com/future-architect/arch-guidelines)（CC BY 4.0）
- [Spring Boot: Sending Email](https://docs.spring.io/spring-boot/reference/io/email.html)、[Spring Boot 4.2 Release Notes](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.2-Release-Notes)
- [Spring Cloud AWS 4.2.2](https://github.com/awspring/spring-cloud-aws/releases/tag/v4.2.2)（`README.md` の対応表、`docs/src/main/asciidoc/ses.adoc`）
- [Obtaining Amazon SES SMTP credentials](https://docs.aws.amazon.com/ses/latest/dg/smtp-credentials.html)、[Using the Amazon SES account-level suppression list](https://docs.aws.amazon.com/ses/latest/dg/sending-email-suppression-list.html)、[Amazon SES tenants](https://docs.aws.amazon.com/ses/latest/dg/tenants.html)
- [Mailpit v1.34.0](https://github.com/axllent/mailpit/releases/tag/v1.34.0)、[Mailpit Chaos](https://mailpit.axllent.org/docs/integration/chaos/)
- [RFC 9989](https://www.rfc-editor.org/info/rfc9989)
