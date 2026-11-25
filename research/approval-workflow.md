# 承認ワークフローの置き場所と権限との関係（見積回答の承認者の調査）

この文書は調査の記録で、merge しない。
見積回答（quote response）に承認者を入れる前に、主要プロダクトが承認ルートをどう持ち、権限や役割とどう関係づけているかを、一次資料で確かめた。
取得日はすべて 2026-11-25 である。
対象はこちらの `yokozi-jp/spring-modulith-ai-dlc`（worktree の base は commit `8dea1056`）で、アプリのコードは書かない。
テナントの役割は今 `ADMIN` と `MEMBER` の 2 つで、`m_membership` に持つ（`docs/web-api/authentication-and-session.md:49-55`、ADR-101）。

## 結論

検証した仮説は「権限（役割から権限へ。`quote:approve` など）はどのテナントでも固定で同じ。テナントごとの違いは、テナントが設定する承認ルール（承認の要否、金額のしきい値、承認者の指名、免除する利用者、直列と並列）を業務データとして持つ。自分の申請は承認できないという規則は domain に常に置く」である。

- 仮説の骨格は、調べた全製品で成り立つ。承認ルートは、権限や役割の仕組みとは別の、顧客ごとの設定データとして持たれている（GitHub、Salesforce、ServiceNow、Dynamics 365、SAP、Workday、Jira Service Management、NetSuite、Zuora、kintone、freee、ジョブカン）。どの製品も、ルートを顧客が設定画面や設定のファイルで変えられる形にしている。
- 「承認できる」は、ルートでの指名（assignment）が必須である。役割や権限だけで承認できる製品はない。ただし多くの製品は、指名に加えて低い水準の資格を求める。ServiceNow は `approval_request_approver` の役割、Zuora は `Zuora Approval User` の権限セット、freee は「入力担当」を承認者に指名できない、GitHub は read 以上の権限を求める。SAP は逆で、承認の符号（release code）ごとの権限オブジェクト `M_EINK_FRG` が承認者を決める。
- つまり `quote:approve` は「承認者に指名されてよい資格」であり、誰が承認するかを決めるのは指名のデータである。この二層は、仮説の言い方より少し細かい。
- 仮説が成り立たないのは次の 3 点である。
  1. 承認の限度額と、承認を要しない利用者を、利用者の行に持つ製品が多い（NetSuite の employee record、Coupa の user record、Dynamics 365 の支出限度額）。テナントの規則の表だけで持つ製品のほうが少ない。
  2. 自己承認の禁止は、市場の既定ではない。Dynamics 365 は既定で提出者の承認を許す。GitHub は任意の設定で、REST の応答例は無効である。Workday、Jira Service Management、Coupa、ジョブカンは、設定か経路の組み方で決める。常に禁止する domain の規則は、市場より厳しい選択である。標準（NIST SP 800-53 AC-5、ASVS 5.0 の V2.3.5）はこの厳しさを支えるが、承認の要否は別の層で決める前提である。
  3. 承認者が 1 人しかいないテナントでは、「常に自己承認を禁止」と「承認者を指名する」を同時に置くと詰む。Dynamics 365 の文書は、この詰みを自ら警告している。出口として、NetSuite は承認者がいなければ自動で承認し、ServiceNow は規則に当たらなければ自動で承認し、Zuora は承認を始めない。
- 条件の評価は、順序を持つ規則の列である。「最初に合ったものを採る」と明示するのは SAP の flexible workflow、Workday、ServiceNow の classic の承認規則（SN1）である。NetSuite は priority、Zuora は `Run After` で順序を持ち、Salesforce は step ごとの entry criteria と `ifCriteriaNotMet` で個別に扱う。合う規則がないときは、自動承認にする製品（ServiceNow、NetSuite）、承認を始めない製品（Zuora の既定）、選べる製品（Zuora の `Auto-approve when no match found`）に分かれる。
- 申請済みの案件は、提出時に承認者を実体化（snapshot）する製品が多い。kintone は「作業者の設定を変更しても、作成済みのレコードに設定された作業者は変更されません」と書く。Salesforce は有効化の後、ステップの追加、削除、並べ替えを禁じる。ServiceNow の承認ステップの表は、適用した規則と条件と chain を持つ。GitHub は環境を消すと待機中のジョブを失敗させる（生の参照の例）。
- 画面の出し分けの契約は、案件ごとに「今の利用者が実行できる操作」をサーバーが返す形が最も安全である。GitHub の API は `current_user_can_approve` を返し、kintone は作業者だけにボタンを出し、作業者を指名しない状態では全員に出す。Zuora は「A non-approver cannot approve or reject the request.」と書く。
- 承認ルートを編集できる人は、承認する人とは別の、設定用の役割や権限セットで分ける製品が多い（ServiceNow の `approval_rule_admin` と `approval_rule_writer`、Zuora の `Zuora Approval Admin`）。管理者がルートを迂回できる機能（GitHub の bypass、ServiceNow の Override）は、監査と無効化の設定を伴う。
- こちらの推奨は、本文の「こちらの設計への意味」にまとめた。要点は、役割は `ADMIN` と `MEMBER` のまま増やさない、承認ルールは tenant_id を持つ表にする、案件ごとの承認の状態に提出時の承認者を実体化する、自己承認の禁止は決定時に domain が利用者の ID を値で受け取って確かめる、画面は案件ごとの `allowedActions` で出し分ける、の 5 つである。

確度は設問ごとに本文に付けた。
高が 1、2、3、9、中が 4、5、6、7、8、10 である。

## 仮説の検証

| 仮説の文                                               | 判定         | 主な根拠                                                       |
| ------------------------------------------------------ | ------------ | -------------------------------------------------------------- |
| 承認ルートは顧客ごとの設定データで、権限の仕組みとは別 | 成り立つ     | 全製品。ServiceNow は設定ごとに表を持つ（SN-AAM1、SN-AAM5）    |
| 権限は固定で、誰が承認するかはデータ（指名）が決める   | 部分的       | 指名は必須。資格を求める製品が多い。SAP は権限が承認者を決める |
| 承認の要否としきい値はテナントの規則として持つ         | 成り立つ     | D365、freee、kintone、Zuora、ServiceNow、SAP                   |
| 承認を要しない利用者はテナントの規則として持つ         | 部分的       | 利用者の行の限度額、役割の有無、提出者の条件で持つ製品が多い   |
| 自己承認の禁止を domain に常に置く                     | 市場より厳格 | 既定は許可の製品が多い。標準は SoD を求める                    |
| 直列と並列のステップはルールデータで持つ               | 成り立つ     | ServiceNow、Zuora、D365、freee、kintone                        |
| 申請済みの案件は、ルールの変更の影響を受けない         | 製品で差     | kintone は snapshot。GitHub は環境の削除で待機を失敗させる     |

## 調べ方と確度の付け方

- 一次資料は、各社の公式の製品文書、API リファレンス、標準の本文である。JavaScript で描画するページは、ベンダーの GitHub にある文書の原文（Microsoft の `dynamics-365-unified-operations-public`）や、文書の取得 API（ServiceNow の `/api/khub`）で読んだ。
- 検索結果の概要文だけで読んだ出典には「概要文のみ」と付ける。本文に届かなかったものは、最後の「届かなかった出典」に書く。
- 「出典が書くこと」と「こちらの推論」を分ける。引用は原文の短い一節にする。
- 確度は、高が「複数の製品の本文で確認した」、中が「1、2 の製品の本文か、概要文のみ」、低が「推論が主」である。

## 設問ごとの証拠

### 設問 1 承認の設定はどこにあるか。「承認できる」は権限か、指名か

結論は次のとおりである。
承認ルートは、全製品で、顧客（テナント、組織、アプリ、環境）ごとの設定データであり、権限や役割の仕組みとは別に持つ。
「承認できる」の根拠は、ルートでの指名が必須で、その上に資格（役割、権限セット、閲覧権限）を求める製品が多い。
確度は高である。

出典が書くこと。

| 製品                    | 設定の置き場                                | 「承認できる」の根拠                                                           | 出典             |
| ----------------------- | ------------------------------------------- | ------------------------------------------------------------------------------ | ---------------- |
| GitHub                  | リポジトリの environment の protection rule | reviewer に指名。reviewer は read 以上の権限が必要                             | GH1, GH2         |
| Salesforce              | オブジェクトごとの ApprovalProcess          | 割り当てられた approver であること                                             | SF1, SF4         |
| ServiceNow（見積）      | 実体ごとに 1 つの Approval Configuration    | Approval User に指名し、かつ `approval_request_approver` の役割が必要          | SN-AAM2, SN-AAM5 |
| ServiceNow（classic）   | Task の表ごとの承認エンジンと承認規則       | approval の行の approver                                                       | SN1, SN3         |
| Dynamics 365 Finance    | workflow の定義（workflow editor で編集）   | 割り当ての種類は Participant、Hierarchy、Workflow user、User の 4 つ           | MS1              |
| Power Automate          | 呼び出す flow の action                     | 指名。環境外の利用者には `Approvals User` の Dataverse の役割が自動で付く      | MS3              |
| SAP S/4HANA             | Customizing の release strategy             | release code ごとに権限オブジェクト `M_EINK_FRG` で、誰が扱えるかを決める      | SAP1, SAP2       |
| Workday                 | business process の定義                     | 承認ステップの Group（多くは役割に基づく security group）                      | WD1              |
| Jira Service Management | workflow の status の approval step         | approver source のフィールドの値。承認者は space の customer であればよい      | JI1, JI2         |
| NetSuite                | 従業員の行と SuiteApprovals の rule         | 従業員の行の supervisor と approver、`Employee Center` の役割が必要            | NS1, NS2         |
| Zuora CPQ               | approval flow、rule、chain                  | chain の approver。設定は `Zuora Approval Admin`、承認は `Zuora Approval User` | ZU1              |
| kintone                 | アプリごとのプロセス管理                    | 作業者に指名。指名がなければ、レコードを見られる全員                           | KT1, KT2         |
| freee                   | 申請経路（テンプレート）                    | 承認者に指名。「入力担当」の権限の人は承認者にできない                         | FR1              |

引用する一節は次のとおりである。

- GitHub：`Enter up to 6 people or teams. Only one of the required reviewers needs to approve the job for it to proceed.`（GH1）。`The reviewers must have at least read access to the repository.`（GH2）。
- Salesforce の REST（承認）：`The current user must be an assigned approver.`（SF4）。
- ServiceNow の見積の承認：`Approval users must have the sn_adv_appr_mgmt.approval_request_approver role assigned to them.`（SN-AAM5）。`A Sales Customer Relationship Management entity can have only one approval configuration.`（SN-AAM2）。
- SAP：`Who may work with which release codes is basically controlled via a system of authorizations (authorization object M_EINK_FRG).`（SAP2、概要文のみ）。
- Jira Service Management：承認者は Jira Service Management のライセンスが要らず、`they just have to be customers of the service space.`（JI1）。
- Zuora：`Users who configure approval flows, groups, chains, and rules need Zuora Approval Admin access. Approvers and users who submit quotes for approval need Zuora Approval User access.`（ZU1）。
- kintone：`作業者を設定していない場合、アクションを実行するためのボタンは、すべてのユーザーに表示されます。`（KT2）。
- freee：`「入力担当」の権限を有するメンバーは、承認者に設定することはできません。`（FR1）。
- NetSuite：`Purchase approvals are generally processed through the Employee Center. You must have the Employee Center role to access the Employee Center.`（NS3）。

こちらの推論。
製品は、資格を「承認の入口の前提」、指名を「誰が決めるか」として分ける。
指名がなくても全員がボタンを押せるのは kintone だけで、作業者が未指名のときである。
これは、承認の統制がゆるくてよい社内の記録用途の既定であり、見積の承認には向かないと考える。

### 設問 2 承認者の指名方法と既定

結論は次のとおりである。
指名の方法は、個人、役割や役職、グループ、上長の連鎖、案件の項目の 5 種類に集まる。
上長の連鎖は、ユーザーの行にある上長の項目か、組織の階層から引く。
既定の指名は、本文に書かれていない製品が多い。
確度は高である。

出典が書くこと。

| 製品                    | 個人           | 役割、役職、グループ                | 上長、階層                       | 案件の項目           | 備考                                                          |
| ----------------------- | -------------- | ----------------------------------- | -------------------------------- | -------------------- | ------------------------------------------------------------- |
| Salesforce              | user           | queue                               | userHierarchyField（Manager 等） | relatedUserField     | adhoc（都度選ぶ）。1 ステップに最大 25 人                     |
| ServiceNow（見積）      | User           | Group                               | Dynamic                          | dot で辿れる項目     | Dynamic は提出時に決まる                                      |
| Dynamics 365            | User           | Participant（group、role）          | Hierarchy                        | Workflow user        | 階層は開始点と終了点を指定                                    |
| SAP                     | user name      | Organization Management の position | なし                             | なし                 | release code は最大 8 個                                      |
| Workday                 | なし           | security group（役割に基づく）      | なし                             | なし                 | Initiator と Prior Approvers を除外できる。承認者を都度足せる |
| Jira Service Management | Approvers 項目 | Approver groups 項目                | 担当サービスの change approvers  | 項目の値             | 要求の種類ごとに事前設定できる                                |
| NetSuite                | Approver       | なし                                | Supervisor                       | なし                 | approver があれば supervisor は連鎖に入らない                 |
| kintone                 | ユーザー       | 組織、グループ                      | ユーザー情報の「上司」項目       | フォームのフィールド | 作業者は 1 人、全員、1 人のうち誰か                           |
| freee                   | 承認者を指定   | 部門と役職                          | 上位部門を含める                 | なし                 | 申請者が都度指定も選べる                                      |
| X-point Cloud           | ユーザー       | グループ、役職                      | 役職は申請者から上へ動的に回る   | なし                 | 未定義ステップで都度指定                                      |

引用する一節は次のとおりである。

- Salesforce の `Approver` の `type`：`adhoc`、`user`、`userHierarchyField`、`relatedUserField`、`queue`（SF1）。`Each step supports up to 25 approvers.`（SF1）。
- ServiceNow の見積の承認の規則：`Approver type` は User、Group、Dynamic の 3 つ（SN-AAM4）。
- NetSuite：`If a purchase approver is selected, the supervisor is no longer part of the approval hierarchy.`（NS1）。
- kintone：「複数の承認ルートを設定する」では、承認ルートを別のアプリのレコードに持ち、ルックアップで取り込む例を示す（KT3）。
- freee：`部門および役職を指定` では、役職ごとに `一人の承認` か `全員の承認` を選ぶ（FR2）。

こちらの推論。
上長の連鎖は、組織の階層のデータを前提にする。
今のこちらにはテナントの組織階層がないので、設計の初期は「利用者の個人の指名」だけにするのが妥当である。
kintone の「承認ルートを別アプリに持つ」例は、ルールをテナントの業務データとして持つ形を、そのまま示している。

### 設問 3 条件、しきい値、承認の省略、免除

結論は次のとおりである。
条件は、順序を持つ規則の列が主流である。「最初に合ったものを採る」と明示するのは SAP の flexible workflow、Workday、ServiceNow の classic の承認規則である。条件には金額、案件の項目、申請者の属性を使う。
合う規則がないときの扱いは、製品ごとに明示される。
「この利用者は承認が要らない」は、専用の免除の表ではなく、(1) 利用者の行の限度額、(2) 提出者の条件、(3) 役割の有無、の 3 つのどれかで表すことが多い。
確度は高である。

出典が書くこと。

| 製品                     | 条件の置き場                               | 評価の順                                   | 合う規則がないとき                                                         | 特定の利用者の免除                                                     |
| ------------------------ | ------------------------------------------ | ------------------------------------------ | -------------------------------------------------------------------------- | ---------------------------------------------------------------------- |
| Salesforce               | process と step の entryCriteria           | step の並び順。process の `processOrder`   | step ごとに `ifCriteriaNotMet`                                             | `allowedSubmitters` と entryCriteria に User の項目を使う              |
| ServiceNow（見積）       | Approval Trigger Condition                 | rule の order と chain                     | 承認が必要な条件に当たらなければ自動で承認                                 | 本文に記述なし（条件は quote の項目か dynamic attributes）             |
| Dynamics 365             | step の Condition                          | workflow の中の順序                        | 条件を満たさない step は実行しない                                         | 本文に記述なし                                                         |
| SAP（flexible workflow） | workflow の start condition                | 定義した順に調べ、最初に当たったものを使う | 本文に記述なし                                                             | 本文に記述なし                                                         |
| SAP（release strategy）  | release condition（特性値）                | release strategy の割り当て                | 本文に記述なし                                                             | 本文に記述なし                                                         |
| Workday                  | condition rule                             | 指定した順に、合うものが見つかるまで       | 本文に記述なし                                                             | 承認ステップの routing restriction で Initiator を除外                 |
| NetSuite                 | 従業員の limit と SuiteApprovals の rule   | rule の priority                           | 金額が limit 以下なら自動承認。supervisor と approver がいなくても自動承認 | 提出者の limit が金額以上、または `Employee Center` の役割を持たない人 |
| Zuora CPQ                | approval flow の entry gate と rule        | flow の `Run After`、rule の `Run After`   | `Auto-approve when no match found` を選べる                                | entry gate の条件に Account などを使う                                 |
| kintone                  | アクションの「実行できる条件」             | アクションごとの条件                       | 条件を満たさないとボタンが出ない                                           | 条件に作業者を使える（本文の例は金額とコメント）                       |
| freee                    | 申請経路の金額分岐、役職分岐（概要文のみ） | 承認ステップごとに条件                     | 条件に当たらないステップは実行しない                                       | 役職分岐で申請者の役職を使う                                           |

引用する一節は次のとおりである。

- Salesforce の step の `ifCriteriaNotMet`：`ApproveRecord`、`RejectRecord`、`GotoNextStep` の 3 値（SF1）。`RejectRecord` は最初のステップだけである。
- Salesforce の REST（提出）：リクエストに `skipEntryCriteria` と `contextActorId`（代理の提出者）を渡せる（SF3）。
- ServiceNow の見積の承認：`If your quote does not meet any configured approval conditions, the approval framework automatically approves the quote without requiring manual review.`（SN-AAM6）。classic の承認規則も、状態を自動で `Approved` にして `auto-approving the task` できる（SN2）。
- Zuora：`If no approval flow or rule applies to the quote, the quote does not enter an approval process.`（ZU3）。`Auto-approve when no match found` の選択肢がある（ZU2）。
- NetSuite：`If they enter an expense report or purchase request below their limit, the transaction is automatically approved.`（NS1）。
- NetSuite の requisition の workflow：`Check User Role` の状態で `Employee Center` の役割がなければ `The status of your request is automatically changed to Approved.`（NS4）。
- Workday：`Condition rules consist of 1 or more conditions that Workday examines when you initiate a business process in the order you specify until it finds a match.`（WD2）。
- SAP の flexible workflow：`If you have several active workflows, the start conditions are checked in the order you have defined. Once the start conditions for a workflow are met, that workflow is used.`（SAP3、概要文のみ）。
- kintone：「10万円未満なら課長承認、10万円以上なら部長承認」の例で、`アクションが実行できる条件` により、金額に応じて表示するボタンを切り替える（KT4）。
- freee（概要文のみ。FR3）：`経路設定において承認ステップに条件を設定することで、合計金額を基準として各承認ステップを実行するかしないかの分岐を設定することができます。`

こちらの推論。
見積回答のパターン 1（承認者なし）は「規則に合わない、または承認を無効にする」で、結果を「自動承認」として記録する形に当たる。
パターン 2（金額による）は、金額のしきい値を持つ規則を優先順に並べる形に当たる。
パターン 3（個人の免除）は、製品では 3 通りに割れる。
免除を「承認者になれない」資格の問題として表す製品（NetSuite の役割の有無）と、提出者の属性で規則を分ける製品（Salesforce の `allowedSubmitters`、freee の役職分岐）と、利用者の限度額で表す製品（NetSuite、Coupa）である。
こちらでは、利用者の行に限度額を足すより、テナントの規則の表に「提出者の免除」の行を持つほうが、役割の定義（`ADMIN` と `MEMBER`）を変えずに済む。

### 設問 4 職務分掌（自己承認の禁止）

結論は次のとおりである。
自己承認の禁止は、確認できた製品では設定として存在し、既定で禁止する製品は見つからなかった。
実現の仕組みは 3 種類ある。
(1) 承認者の候補から提出者を除く（Jira、Workday）、(2) 決定の時点で拒否する（GitHub、Dynamics 365）、(3) 上長に回す（Coupa）。
標準は、職務の分掌と、重要な処理に複数の人の承認を求めることを要求する。
確度は中である。ServiceNow、Salesforce、Money Forward の既定は本文に届かなかった。

出典が書くこと。

| 製品                    | 既定                                 | 設定                                         | 実現の仕方                                         | 出典     |
| ----------------------- | ------------------------------------ | -------------------------------------------- | -------------------------------------------------- | -------- |
| Dynamics 365 Finance    | 提出者も承認できる                   | workflow の `Disallow approval by submitter` | 設定すると提出者は承認できない                     | MS2      |
| GitHub                  | 任意の設定。REST の応答例は false    | environment の `Prevent self-review`         | 起動した人は、reviewer でも承認できない            | GH2, GH5 |
| Workday                 | 設定による                           | step の routing restriction                  | `Exclude Initiator`、`Exclude Prior Approvers`     | WD3      |
| Jira Service Management | 設定による                           | `Exclude approvers from`                     | 指定した項目の人は、その workflow では承認できない | JI2      |
| Coupa                   | 概要文のみ。自己承認の限度額を設ける | 利用者の `Self Approval Limit`               | 自己承認を許さない chain では上長に回す            | CP1, CP2 |
| ジョブカン              | 申請者が自分の承認を飛ばせる         | 経路の組み方                                 | ステップ 1 に自分がいるときに限る                  | JC1      |
| Money Forward           | 不明                                 | 「申請者本人による承認可否」（題名のみ）     | 本文に届かず、中身は不明                           | MF1      |
| X-point Cloud           | 本文に記述なし                       | 承認除外者の設定                             | 本文は「承認除外者の設定ができる」とだけ書く       | XP1      |

引用する一節は次のとおりである。

- Dynamics 365：`Yes, a submitter of a workflow can approve the workflow unless the setting Disallow approval by submitter is enabled for the workflow.`（MS2）。`By default, this setting is No`（MS1）。
- Dynamics 365：`If you set the workflow to Disallow approval by submitter and include approval steps with a final approver, make sure the final approver isn't a user who typically submits documents to workflow, since they can't complete the approval.`（MS1）。禁止すると詰む例を、製品の文書が自ら警告している。
- GitHub：`If you enable this setting, users who initiate a deployment cannot approve the deployment job, even if they are a required reviewer.`（GH2）。REST は `prevent_self_review boolean Whether or not a user who created the job is prevented from approving their own job.`（GH5）。
- Jira Service Management：`Any users that appear in the fields you select here will not be able to approve work items that use this workflow.`（JI2）。
- Workday：`When you configure the restrictions to skip everybody eligible to receive the step, Workday skips the step and specifies it as Not Required.`（WD3）。この一節は Send Back の説明の中にあるが、除外で適格者が残らなければ、承認が要らない扱いに倒れると読める。
- ジョブカン：`申請者は、その申請書に設定された自分の承認をスキップすることができます。ただし、ステップ1に自分が指定されている場合のみスキップ可能です。`（JC1）。

標準が書くこと。

- NIST SP 800-53 Rev. 5 の AC-5 は、分離すべき職務を `Identify and document` すること、`Define system access authorizations to support separation of duties.` の 2 項目を要求する。解説は `Separation of duties addresses the potential for abuse of authorized privileges and helps to reduce the risk of malevolent activity without collusion.` と書く（NIST1）。
- NIST の用語集（SP 800-192 の定義）は、`the person authorizing a paycheck should not also be the one who can prepare them` を例にし、職務の分掌を、役割の組を静的に分ける方法と、アクセスの時点で強制する動的な方法に分ける。動的な例は two-person rule で、`The first user to execute a two-person operation can be any authorized user, whereas the second user can be any authorized user different from the first` である（NIST2）。自己承認の禁止は、この動的な分掌に当たる。
- ASVS 5.0.0 の V2.3.5（レベル 3）：`Verify that high-value business logic flows require multi-user approval to prevent unauthorized or accidental actions. This could include but is not limited to large monetary transfers, contract approvals, access to classified information, or safety overrides in manufacturing.`（ASVS1）。V2.3.1 は、手順を飛ばさずに順に処理することを求める（レベル 1）。
- ASVS 5.0.0 の V8.3.1：`Verify that the application enforces authorization rules at a trusted service layer and doesn't rely on controls that an untrusted consumer could manipulate, such as client-side JavaScript.`（ASVS1）。
- OWASP の Authorization Cheat Sheet は、`separation` を方針の管理と実施を分ける文脈で 1 回使うだけで、職務分掌（SoD）には触れていない。`segregat` と `duties` の語もない（OWASP1）。

こちらの推論。
常に禁止する規則は、標準と製品の設定の両方で支持できるが、製品の既定ではない。
禁止を「承認者の候補から除く」で実現すると、候補が空になる。
Workday の本文は、制限で適格者が残らないとき、step を `Not Required` として飛ばすと書く（Send Back の説明の中にあり、承認の経路の除外にも同じ動作かは確かめていない）。
Dynamics 365 は、最終承認者が提出者だと承認を完了できないと警告する。
こちらは、候補が空になるときの扱い（拒否して提出させない）を、最初に決めておく必要がある。

### 設問 5 代決、再割り当て、取り戻し

結論は次のとおりである。
代決は「承認者が期間を決めて、他の利用者に任せる」ルートの機能として持つ製品が多く、権限の機能ではない。
取り戻し（recall）は提出者に与え、再割り当てと迂回は管理者に与える。
確度は中である。Salesforce の代決の詳細は本文に届かなかった。

出典が書くこと。

| 製品                  | 代決                                                                                                                                                              | 取り戻し、再割り当て                                                                                                            | 出典                      |
| --------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------- |
| ServiceNow（classic） | 利用者が自分の Delegates に追加。期間と、承認、割り当て、通知の範囲を選ぶ。Service Portal から代理で承認するには `approval_admin` か `approver_user` の役割が要る | 記述なし                                                                                                                        | SN4                       |
| ServiceNow（見積）    | admin か `delegation_admin` が規則を作る。Employee Center で委任                                                                                                  | 提出者は `Recall approval request` で全ての待機を取り消し、Draft に戻す。上書きは `approval_admin` と `approval_request_writer` | SN-AAM7, SN-AAM8, SN-AAM9 |
| Dynamics 365          | 手動の `Delegate`、一括、User options の自動委任（範囲は All、Module、Workflow、期間つき）                                                                        | 委任は work item を相手に付け替える                                                                                             | MS4                       |
| Workday               | `Manage Delegations`。期間、既定の代替者、tenant の設定が必要                                                                                                     | `Reassign Steps`、`Cancel`、`Rescind`                                                                                           | WD4, WD5                  |
| Salesforce            | step ごとの `allowDelegate`。承認者が代理を指名                                                                                                                   | `allowRecall` が false のときは管理者だけが取り戻せる                                                                           | SF1                       |
| kintone               | 「作業者以外でも実行できるアクション」で、副部長などを指定                                                                                                        | 現在の作業者の変更はアプリ管理権限の利用者だけ                                                                                  | KT2, KT5                  |
| ジョブカン            | 代理承認者に設定された利用者だけが、代理で承認、差し戻し、却下できる                                                                                              | 代理の申請は承認の省略ができない                                                                                                | JC2, JC1                  |
| Zuora CPQ             | 本文では確認できず                                                                                                                                                | 提出者が取り消せる（Canceled）                                                                                                  | ZU4                       |

引用する一節は次のとおりである。

- ServiceNow：`Role required: none to delegate approvals to another user`、`For a delegate to approve on your behalf from a Service Portal, they must have the approval_admin or approver_user role.`（SN4）。
- Workday：`Select the Apply Routing Restrictions during Delegation check box on the Edit Tenant Setup - Business Processes task.` と、`Enable business process delegation in the Policy Restrictions section of the security policy for the Expense Report Event business process.`（WD5）。代理にも、`Exclude Initiator` などの routing restriction が効く。
- ServiceNow の見積：`Recalling an approval request cancels any pending approval steps and returns the quote to the Draft state.`（SN-AAM8）。
- kintone：`代理で承認したい` の例は、「作業者以外でも実行できるアクションは、作業者が設定されているステータスでのみ設定できます」（KT5）。

こちらの推論。
代決は、不在の承認者の案件を詰まらせない仕組みである。
最初の版では、代決を持たず、案件の取り戻しと再提出で足りるかを確かめる。
代決を入れるときは、設問 4 のとおり、代決の相手が提出者であるべきでない（Workday は、除外の制限が代理にも効く）。

### 設問 6 申請済みの案件と、ルートの編集

結論は次のとおりである。
提出時に承認者を案件の側へ持つ製品（kintone、ServiceNow の見積）では、ルートの編集は新しい提出にだけ効く。
有効化の後の編集を禁じる製品（Salesforce）と、版を作る製品（Dynamics 365）もある。
生の参照のまま編集の影響を受ける例も、GitHub にある。
確度は中である。明示された製品は 3 つで、他は推論である。

出典が書くこと。

| 製品               | 申請済みの案件                                                                                                | 記録のロックと版                                                                                                       | 出典                       |
| ------------------ | ------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | -------------------------- |
| kintone            | 作業者を変えても、作成済みのレコードの作業者は変わらない                                                      | 状態ごとのレコードのアクセス権で、承認済みの変更を止める                                                               | KT5, KT6, KT10             |
| Salesforce         | 有効化の後は、step の追加、削除、並べ替え、reject と skip の動作の変更ができない                              | 提出すると自動でロック。`recordEditability` で編集できる人を決める。`finalApprovalRecordLock` で承認の後もロックできる | SF1                        |
| ServiceNow（見積） | 承認ステップの表が、適用した規則、条件、chain の情報を持つ                                                    | `Requested for review` の間は編集不可。再提出は `smart re-approval` で前の承認を再利用できる                           | SN-AAM1, SN-AAM4, SN-AAM11 |
| Dynamics 365       | 実行中の案件は、保存された workflow の instance が持つ。有効にした新しい版との関係は本文にない                | 保存時に変更の comment を求め、`Activate the new version` で版を有効にする                                             | MS5, MS6                   |
| NetSuite           | `No Approval Rerouting Regardless of Change in Record Amount` を選べる                                        | `Lock Record for Editing Based on Status`。Pending Approval では管理者以外が編集できない                               | NS2, NS4                   |
| GitHub             | environment を消すと、待機中の job は自動で失敗する                                                           | 管理者は、待機中の job を bypass できる（無効にできる）                                                                | GH1, GH4                   |
| SAP Ariba          | 概要文のみ。承認済みの変更は、`Auto-Approve if Quantity or Amount Has Not Increased` で規則ごとに扱いを決める | なし                                                                                                                   | AR1                        |

引用する一節は次のとおりである。

- kintone：`作業者の設定を変更しても、作成済みのレコードに設定された作業者は変更されません。`（KT6）。`組織／グループの設定変更後にアクションを実行したレコードでは、変更内容が［現在の作業者］に反映されます。`（KT5）。作業者に組織やグループを指名するときは、アクションを実行した時点のメンバーで確定する。
- Salesforce：`After an approval process is activated, you can't add, delete, or change the order of the steps or change its reject or skip behavior, even if the process is inactive.`（SF1）。`When a record is submitted for approval, it's automatically locked to prevent other users from editing it during the approval process.`（SF1）。
- ServiceNow の見積の承認：`Approval Steps: Detailed steps within an approval request, extended from the Task table, containing the applied rules, conditions, and chain information.`（SN-AAM1）。
- GitHub：`Deleting an environment will delete all secrets and protection rules associated with the environment. Any jobs currently waiting because of protection rules from the deleted environment will automatically fail.`（GH1）。

こちらの推論。
snapshot にするかどうかは、製品の文書では多くが書かれていない。
ただし ServiceNow の見積と kintone は、案件の行が承認者の割り当てを持つので、規則を変えても影響しない。
こちらでも、提出の時点で承認者を案件の側の行に実体化し、その時点のルールの版の ID を持たせる形が、一番説明しやすい。

### 設問 7 ルートを編集できる人と、変更の監査

結論は次のとおりである。
ルートの編集は、承認する役割とは別の管理用の役割や権限セットに分ける製品が多い。
変更の監査が文書に書かれた製品は少ない（Dynamics 365 の変更 comment、Ariba の監査ログ）。
確度は中である。

出典が書くこと。

| 製品                    | 編集できる人                                                                                                                                                                                                     | 監査、承認                                                                                      | 出典             |
| ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------- | ---------------- |
| ServiceNow（見積）      | `approval_rule_admin`（作成、更新、削除）、`approval_rule_writer`（削除不可）、`approval_rule_viewer`。scripted の条件は `admin` だけ。設定の編集は application scope が Advanced Approval Management であること | 記述なし                                                                                        | SN-AAM1, SN-AAM4 |
| Zuora CPQ               | `Zuora Approval Admin`                                                                                                                                                                                           | 記述なし                                                                                        | ZU1              |
| GitHub                  | 組織のリポジトリでは `admin access`                                                                                                                                                                              | 管理者の bypass は既定で許可。無効にもできる                                                    | GH1, GH2         |
| Jira Service Management | approval step の追加は Jira administrator。要求の種類の承認者の事前設定も Jira administrator。顧客に承認者を選ばせる設定は space admin                                                                           | 記述なし                                                                                        | JI2, JI3         |
| kintone                 | プロセス管理の編集の権限は記述なし。`上司` の項目の追加は `cybozu.com共通管理` の権限。現在の作業者の変更はアプリ管理権限                                                                                        | 記述なし                                                                                        | KT3, KT5         |
| ジョブカン              | `Admin、管理者、システム担当` の権限                                                                                                                                                                             | 記述なし                                                                                        | JC3              |
| NetSuite                | Administrator の役割が機能を有効にし、役割を付け、承認者と limit を決める                                                                                                                                        | 記述なし                                                                                        | NS5              |
| Dynamics 365            | 編集の権限は記述なし                                                                                                                                                                                             | 保存時の変更 comment と版                                                                       | MS6              |
| SAP Ariba               | `Customer Administrator` の group                                                                                                                                                                                | `Audit and approve the updates before deployment`、`Audit logs of all activities`（概要文のみ） | AR2              |

引用する一節は次のとおりである。

- ServiceNow：`If you have the admin role, you can use scripts to specify approval conditions if the Advanced condition option is turned on. Only users with the admin role can enter scripted conditions.`（SN-AAM4）。`Approval rule admin ... Creates, updates, and deletes approval configurations, chains, approval trigger conditions, approval rules, and approval users and groups.`（SN-AAM1）。
- ServiceNow の上書き：`Role required: approval_admin (platform role) and sn_adv_appr_mgmt.approval_request_writer`（SN-AAM9）。待機中のステップを、承認か拒否として進める。
- GitHub：`To configure an environment in an organization repository, you must have admin access.`（GH1）。`By default, administrators can bypass the protection rules and force deployments to specific environments.`（GH2）。無効にする選択肢は `Allow administrators to bypass configured protection rules` の解除（GH1）。
- Jira：`You need to be a Jira administrator to add an approval step to a workflow.`（JI2）。要求の種類の承認者の事前設定は `You must be a Jira administrator`、顧客に承認者を選ばせる設定は `You need to be a space admin`（JI3）。

こちらの推論。
編集の権限を承認の権限と分ける理由は、ルートを書き換える人が、自分の案件の承認者を決められてしまうからである。
管理者の迂回は、監査の記録を伴う強い操作として別に扱う。

### 設問 8 画面と API の契約

結論は次のとおりである。
承認の状態と操作の出し分けは、画面側のボタンの表示だけでなく、サーバーが「今の利用者に許す操作」を返す形が最も扱いやすい。
API が明示するのは GitHub と kintone で、他は画面の中で暗黙に出し分ける。
確度は中である。

出典が書くこと。

| 製品       | 状態の見せ方                                                                    | 操作の出し分け                                                     | API が返すもの                                                                                                                                         | 出典                        |
| ---------- | ------------------------------------------------------------------------------- | ------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------- |
| GitHub     | `Review deployments` の通知。Approve and deploy と Reject                       | 自己承認を禁止した環境では、自分が起動した run を承認できない      | `current_user_can_approve`、`reviewers`（type は User か Team）、`wait_timer`                                                                          | GH3, GH4                    |
| kintone    | ステータス、現在の作業者、ステータスの履歴、未処理の一覧                        | 作業者にだけボタンを出す。作業者が未設定なら、閲覧できる全員に出す | JS API の `kintone.app.record.getStatusActions()` が `name` と `nextStatus` を返す。REST のプロセス設定は `actions[].type` が `PRIMARY` か `SECONDARY` | KT2, KT8, KT9               |
| ServiceNow | 見積の `Approvals` タブの step card。状態、所要時間、escalation                 | step card の More options で Approve か Reject。管理者は Override  | 記述なし                                                                                                                                               | SN-AAM9, SN-AAM10, SN-AAM11 |
| Salesforce | `ProcessInstanceWorkitem` が、利用者の待機中の承認依頼を表す                    | `ActorId` が承認する人、`OriginalActorId` が元の割り当て           | REST の承認は `instanceStatus`、`actorIds`、`newWorkitemIds`、`errors`、`success`                                                                      | SF5, SF4                    |
| Zuora CPQ  | `My Approvals`。状態は Not Submitted、In Progress、Approved、Rejected、Canceled | 承認者が Approve か Reject、提出者が取り消し                       | 記述なし                                                                                                                                               | ZU4                         |
| NetSuite   | `Pending Approval` の状態、`Approval History` の subtab                         | `Only the current approver has access to these options.`           | 記述なし                                                                                                                                               | NS4, NS2                    |

引用する一節は次のとおりである。

- GitHub（pending deployments の応答の項目）：`"current_user_can_approve": true`、`"reviewers": [ { "type": "User", ... }, { "type": "Team", ... } ]`（GH3）。承認の API は `Required reviewers with read access to the repository contents and deployments can use this endpoint.`（GH3）。
- kintone：`作業者を設定した場合、アクションを実行するためのボタンは、作業者だけに表示されます。`（KT2）。REST のプロセス設定の `actions[].type` は、`PRIMARY: アクション実行前のステータスで作業者に指定されたユーザーのみが実行できるアクション`、`SECONDARY: 「作業者以外でも実行できるアクション」`（KT8）。
- Zuora：`A non-approver cannot approve or reject the request.` と、`Send to Zuora` を承認の前に押すと `returns an error indicating that the quote has not been approved`（ZU4、ZU3）。画面の出し分けと別に、サーバーが状態を強制する。

こちらの推論。
GitHub の `current_user_can_approve` は、案件ごとの `allowedActions` を、承認だけに絞った形である。
画面側が role から自前で推測すると、指名のデータ（ルートの変更、代決）と食い違う。
サーバーが返す値を 1 つの関数で作り、強制にも同じ関数を使うのが、食い違いを防ぐ。

### 設問 9 データモデル

結論は次のとおりである。
製品が公開する形は、(1) 設定の側（ルール、ステップ、承認者）、(2) 実行の側（要求、ステップの実体、承認者ごとの割り当て、決定）、(3) 履歴、の 3 層に分かれる。
確度は高である（名前の付いた製品の範囲）。

出典が書くこと。

| 製品                    | 設定                                                                                                           | 実行                                                                         | 履歴                                              | 出典          |
| ----------------------- | -------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------- | ------------------------------------------------- | ------------- |
| Salesforce              | `ApprovalProcess`、`ApprovalStep`、`ApprovalEntryCriteria`、`Approver`                                         | `ProcessInstance`、`ProcessInstanceWorkitem`（`ActorId`、`OriginalActorId`） | `ProcessInstanceStep`（`StepStatus`、`Comments`） | SF1, SF2, SF5 |
| ServiceNow（見積）      | Approval Configuration、Trigger Condition、Rule、Chain、Approval User、Approval Group                          | Approval Request、Approval Step（Task を拡張）                               | Approval Step の状態と comment                    | SN-AAM1       |
| ServiceNow（classic）   | 承認規則、承認エンジン                                                                                         | `sysapproval_approver`（Approver、State、Approving、Comments）               | 承認の audit history と activity stream           | SN1, SN3, SN5 |
| Zuora CPQ               | approval flow（entry gate）、approval rule、approval chain、approval group                                     | 承認要求（状態、提出者、提出日、現在のステップ、現在の承認者）               | `My Approvals`                                    | ZU1, ZU3      |
| NetSuite                | 従業員の supervisor、approver、purchase limit、approval limit。SuiteApprovals の rule                          | transaction の `Approval History` の subtab                                  | 同左                                              | NS1, NS2      |
| kintone                 | `states`（`assignee`、`index`）、`actions`（`from`、`to`、`filterCond`、`type`、`executableUser`）、`revision` | レコードの状態と現在の作業者                                                 | ステータスの履歴                                  | KT8           |
| GitHub                  | environment の `reviewers`、`prevent_self_review`、`wait_timer`                                                | pending deployment                                                           | deployment の履歴                                 | GH5, GH3      |
| Jira Service Management | workflow の approval step（承認者の項目、人数、除外する項目）                                                  | 要求の承認者の項目の値                                                       | 要求の活動の履歴                                  | JI2           |

引用する一節は次のとおりである。

- ServiceNow の classic の承認：`Approver`、`State`（Not Yet Requested、Requested、Approved、Rejected）、`Approving`（`A document_id reference field to the record being approved, on any table`）、`Comments`（SN3）。

こちらの推論。
製品の公開形は、こちらの案にそのまま写せる。
設定の側はテナントごとの表、実行の側は案件ごとの行、決定は追記だけの行にする。

### 設問 10 仮説の検証

結論は冒頭の表のとおりである。
成り立つのは、承認ルートが業務データであることと、金額のしきい値と優先順の規則を持つことである。
成り立たないのは、「承認の資格を持たない利用者の免除は、テナントの規則だけに持つ」という点と、「自己承認の禁止は市場の既定」という点である。
確度は中である。

仮説に反する製品。

- NetSuite は、承認の限度額と、承認を要しない上限（purchase limit）を、従業員の行に持つ。自分の金額が limit 以下なら、承認の経路に入らない（NS1）。
- Coupa は、利用者の行に `Self Approval Limit` と `Approval Limits` を持つ（概要文のみ。CP1、CP2）。
- Dynamics 365 は、承認の経路が requester の `spending limit` や department manager の `signing limit authority` を超えたかで決まる（MS7）。
- SAP の release strategy は、承認の符号と権限オブジェクトを使い、承認の資格を権限で持つ（SAP2）。

こちらの推論。
限度額を利用者の行に持つ型は、承認者が多い組織で、上長の階層から連鎖を自動で作る用途に向く。
見積回答の承認者は少数で、金額の規則で足りるので、この型は今は採らない。
将来「承認者ごとの限度額」が要るときは、承認者の行に列を足せる。

## こちらの推論

- 承認の設定は、権限の設定とは別の層である。製品は、認可の層（誰がどの操作を呼べるか）と、承認の層（誰がこの案件を決めるか）を分けている。認可の層は、承認の層が正しく働くための下限（資格）として使う。
- 資格を役割で持つか、権限セットで持つかは、製品の選択である。こちらの `ADMIN` と `MEMBER` は、資格の下限としては十分に粗い。承認者を指名できる利用者を「そのテナントの有効な所属」に限れば、新しい役割は要らない。
- 承認者の指名は、データの整合の問題でもある。承認者の行が、他のテナントの利用者を指さないこと、所属を外された利用者が承認者として残らないことを、表の制約と提出時の再確認で守る。ADR-108 の tenant_id を含む複合外部キーの形に合う。
- 自己承認の禁止は、承認の層の中の規則であり、役割では表せない。NIST の用語集が書く dynamic な職務分掌である。こちらの `research/authorization` の調査（`origin/research/authorization:research/authorization.md`、merge しない記録）は、人に依る業務の規則が入ったら「domain が利用者の ID を値で受け取り、不変条件として確かめる」と書いている。この形が、そのまま当たる。
- 「承認なし」の結果は、飛ばしたのではなく、規則に基づく自動承認として記録する。承認の監査（誰が決めたか、決めなかった理由は何か）を、1 つの形で読めるからである。

## こちらの設計への意味

提案は、ADR にする前の下書きである。
決めるのは利用者であり、ここでは各項目に推奨を付ける。

### a 権限と役割（固定、コードに置く）

- 役割は今の `ADMIN` と `MEMBER` のままにする。見積回答のために役割を足さない。
- 承認者の資格は「そのテナントの有効な所属（`m_membership`）であること」とする。`quote:approve` のような権限の名前は、今は作らない。承認者になれるかどうかは、承認ルールの指名で決まる。新しい役割（閲覧専用など）が入った時点で、freee の「入力担当は承認者にできない」と同じ形の資格の検査を足す。
- 承認ルールの編集（方針の作成、更新、承認者の指名、免除の登録）は `ADMIN` だけにする。`research/authorization` が提案した、CommandHandler の `@PreAuthorize("hasRole('ADMIN')")` で止める（今の main には `@PreAuthorize` も `@EnableMethodSecurity` もなく、`/api/current-tenant/members` の配下だけが `TenantSecurityCustomizer` の `hasRole("ADMIN")` で止まる。`docs/web-api/authentication-and-session.md:152-154`）。
- 見積の提出は `ADMIN` と `MEMBER` の両方にできる。承認の決定は、役割ではなく、案件に割り当てられた承認者だけができる。`ADMIN` に承認の迂回を持たせない（GitHub の bypass と ServiceNow の Override は、迂回の入口になる）。

### b テナントの承認ルールのデータ（業務データ、tenant_id を持つ）

最小の項目は次のとおりである。表の名前は仮である。

| 表                           | 最小の項目                                                                        | 3 つのパターンとの対応                           |
| ---------------------------- | --------------------------------------------------------------------------------- | ------------------------------------------------ |
| quote_approval_policy        | tenant_id（主キー）、approval_required、currency、version、updated_by、updated_at | パターン 1 は approval_required が false         |
| quote_approval_rule          | tenant_id、id、amount_at_least（0 以上の整数。0 は金額を問わない）                | パターン 2 は amount_at_least の違う規則を並べる |
| quote_approval_rule_approver | tenant_id、rule_id、user_id                                                       | 規則に当たったときの承認者の指名                 |
| quote_approval_exemption     | tenant_id、user_id（提出者）、created_by、created_at、reason                      | パターン 3 の免除                                |

- 評価の順は、(1) approval_required が false なら承認不要、(2) 提出者が免除の行にいれば承認不要、(3) 規則を amount_at_least の降順に調べ、案件の金額が amount_at_least 以上の最初の規則を採る、(4) どの規則にも届かない金額は承認不要、とする。条件が金額だけの間は、順が amount_at_least で決まるので、priority の列は要らない。条件を足すときに priority を足す（SAP、Workday、NetSuite、Zuora が順序を持つのと同じ形）。
- 規則が 0 件の方針は、approval_required を true にして保存できない（設定の漏れで全件が素通しになるのを防ぐ）。
- 金額で承認者が変わるとき、採るのは 1 つの規則だけで、上の規則の承認者を足し合わせない（足し合わせは、後で step_no で足す）。
- 承認不要の結果は、案件の側に `NOT_REQUIRED` と理由（`POLICY_DISABLED`、`SUBMITTER_EXEMPT`、`BELOW_THRESHOLD`）を記録する。
- 金額は、案件の合計を最小の通貨単位の整数で持つ。方針の `currency` と一致しない案件は、承認を要するとして扱う（Dynamics 365 は system currency の設定を要し、NetSuite は従業員の通貨に換算する。こちらは単一通貨から始める）。
- 直列と並列を後で足すときは、増やすのは加法の変更だけにする。規則の承認者の行に `step_no`（既定 1）と、ステップごとの完了の方式（ANY、ALL。後で N 人）の列を足す。ステップの表を新設してもよい。Zuora は Sequential と Parallel、`Any` と `All must approve` の 2 軸で持ち、ServiceNow は chain の order で直列と並列を表す。
- 方針と規則は、更新ごとに版を上げる。提出した案件が持つのは、版と、その時点の承認者の写しである。

### c 案件ごとの状態（提出時に実体化）

| 表                        | 最小の項目                                                                                                                |
| ------------------------- | ------------------------------------------------------------------------------------------------------------------------- |
| quote_approval_request    | tenant_id、id、quote_id、quote の金額と通貨の写し、status、reason、policy_version、requested_by、requested_at、decided_at |
| quote_approval_assignment | tenant_id、request_id、approver_user_id、step_no、decision（PENDING、APPROVED、REJECTED）、decided_at、comment            |

- status は `NOT_REQUIRED`、`PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`（取り戻し）の 5 値にする。
- 決定は追記だけにして、更新で書き換えない。再提出は新しい request の行にする。
- 日時は `Instant` と `Clock` で、マイクロ秒精度にする（日時の規約）。
- 承認が `PENDING` の間は、見積を読み取り専用にする（Salesforce の lock、NetSuite の lock、ServiceNow の状態）。承認の後に金額が変わったら承認を無効にして再提出させる（NetSuite の re-approval、ServiceNow の smart re-approval、Zuora の再提出）。

### d 常に効く domain の規則

1. 承認の決定をする利用者は、その案件の提出者ではない。domain のメソッドが、決定する利用者の ID を値で受け取って確かめる。拒否は 422（業務のルール）にする。
2. 決定できるのは、その request に `PENDING` で割り当てられた承認者だけである。別の承認者の行や、決定済みの行には決定できない（409）。
3. 提出時に承認者の候補から提出者を除く。除いた後に承認者が 0 人なら、提出を 422 で拒否する（Workday のように `Not Required` に倒さない）。
4. 承認不要の結果は理由とともに記録する。理由のない `NOT_REQUIRED` は作れない。
5. 見積の送付や確定は、`APPROVED` か `NOT_REQUIRED` の案件でだけ許す。Zuora の `Send to Zuora` と同じく、サーバーが状態を強制する。
6. `ADMIN` は承認の決定を代わりにしない。迂回が要る場合は、別の操作（取り戻しと再提出、または方針の変更）に分ける。
7. 承認ルールの編集は、提出済みで決定前の案件の承認者を変えない。

### e 画面の契約

- 権限の取得は今のとおりで、役割は選んだテナントの所属から来る（ADR-101）。承認ルールの管理画面は `ADMIN` だけに出す。画面の制御は誤操作を防ぐためで、保護はバックエンドが担う（`docs/frontend/authorization-ui.md`）。
- 案件の応答に、承認の状態と、今の利用者に許す操作を入れる。

```text
approval: {
  status: PENDING,
  reason: null,
  steps: [ { stepNo: 1, assignments: [ { approver: {...}, decision: PENDING } ] } ],
  allowedActions: [ APPROVE, REJECT ]
}
```

- `allowedActions` は、domain の同じ関数が作り、決定の強制にも使う。値は `SUBMIT`、`APPROVE`、`REJECT`、`RECALL` から、利用者と状態で決める。GitHub の `current_user_can_approve` が 1 つの真偽値であるのに対し、こちらは操作の配列にして、後から足せるようにする。
- 承認者の「自分宛の一覧」は、`status = PENDING` と自分の割り当てで引く 1 本の検索にする（kintone の `（作業者が自分）` の一覧、Zuora の `My Approvals`）。
- 認可の表のテスト（`research/authorization` が提案したもの。今の main にはない）を入れるときは、行として承認の操作を足し、列に「提出者」「承認者」「指名されていない member」「admin」「別のテナント」を置く。承認者でない利用者の決定が 403 か 404 で、提出者の決定が 422 であることを、表で確かめる。

### f 利用者が決める未決の事項と推奨

| 番号 | 決めること                                             | 推奨                                                                                     |
| ---- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------- |
| 1    | 自己承認の禁止を、設定でなく常時にするか               | 常時にする。免除は「承認不要」の経路で表し、承認の決定は常に別の人にする                 |
| 2    | 承認者が 1 人のテナントの詰まり                        | 承認不要の方針（または免除の登録）で出す。迂回の操作は作らない                           |
| 3    | 免除を利用者の行でなく、方針の表に持つか               | 方針の表に持つ。登録した管理者と理由を残す。免除は提出にだけ効き、承認の決定には効かない |
| 4    | どの規則にも届かない金額と、規則が 0 件の方針の扱い    | 届かない金額は承認不要（BELOW_THRESHOLD）。規則が 0 件の方針は、有効にして保存させない   |
| 5    | 承認者の資格                                           | そのテナントの有効な所属であること。`ADMIN` であることは要らない                         |
| 6    | 方針の編集者が、自分の案件に影響する方針を変えてよいか | 変えてよい。ただし版と更新者を残し、提出済みで決定前の案件には効かせない                 |
| 7    | 金額の通貨                                             | 方針に 1 つの通貨を持ち、違う通貨は承認を要するとして扱う                                |
| 8    | 不在の承認者の案件                                     | 最初は、取り戻しと再提出、または方針の変更で出す。代決は設けない                         |
| 9    | 承認者を複数にしたときの完了の方式                     | 最初は ANY のみ。ALL と直列は、後から step_no と完了の方式の列で足す                     |
| 10   | 職務分掌の対象を、提出者だけにするか                   | 提出者に加え、見積の作成者も承認者から除く（NIST の用語集は、履歴に基づく分掌を挙げる）  |

### g 今は作らないもの

- 承認の役割と権限を、テナントごとに編集する仕組み（役割の表、権限の表、編集画面）。ABAC や方針エンジンも入れない（`research/authorization` の提案のとおり）。
- 上長の連鎖、組織の階層、役職での指名（組織の階層のデータがない）。
- 代決、escalation、期限、リマインダー、メールでの承認。
- 承認者ごとの限度額（NetSuite、Coupa の型）と、金額以外の条件（案件の項目、顧客、割引率。ServiceNow と Zuora は条件にできる）。
- 直列と並列を表す専用の言語や BPMN の engine。Dynamics 365、Workday、ServiceNow は workflow の engine だが、こちらは小さな表で足りる。
- 管理者の迂回（GitHub の bypass、ServiceNow の Override）。

## 届かなかった出典

- Salesforce Help（`help.salesforce.com`）：JavaScript で描画し、本文を取れなかった。承認の考慮事項、`Modify All Data` と `Customize Application` の権限、委任の詳細、自己承認の既定は未確認である。Salesforce は開発者向けの文書（Metadata API と SOAP と REST のリファレンス）の本文だけを使った。
- Dynamics 365 Sales の見積の承認：公式文書の本文を取れなかった。Power Automate と Dynamics 365 Finance の文書を使った。
- ServiceNow で自己承認の扱い：文書の検索で該当する本文が出なかった。classic と見積の承認の両方で、既定は確認できていない。
- SAP Help（`help.sap.com`）の本文：ページの取得 API が 500 を返した。検索 API の概要文だけを使ったので、SAP の記述は確度を下げる。
- Coupa の Success Portal、Money Forward の support：bot 判定で 403 だった。検索の概要文と題名だけを使った。
- X-point Cloud は機能の紹介のページの本文を読んだが、承認除外者と代理の詳細はない。Create!Webフローは検索の概要文だけで、本文は取っていない。この文書の表には載せていない。
- Workday：`doc.workday.com` の 4 ページは読めた。承認ステップを飛ばす条件の専用のページと、役割の定義のページは取っていない。
- Ariba は概要文のみ。Zuora Workflow（汎用の workflow）、楽楽精算、Money Forward の本文、Coupa の本文は読んでいない。
- 標準：ASVS 5.0.0 の V2.3 と V8、ASVS 4.0 系の V11 を `separation`、`segregat`、`duties` で検索したが、一致はなかった。承認に関わる項は 5.0.0 の V2.3.5（multi-user approval）だけである。NIST の職務分掌は AC-5 と用語集の定義である。

## 出典

取得日は、すべて 2026-11-25 である。
`[概要文のみ]` は、検索結果の概要文だけで読んだことを指す。
Salesforce は開発者向けの文書だけ（Help は届かず）、ServiceNow は Brazil 版の公開文書の API で本文を取った。

- NIST1：NIST SP 800-53 Rev. 5、AC-5 Separation of Duties。OSCAL の catalog（`usnistgov/oscal-content`、版 5.2.0）の `ac-5`。<https://github.com/usnistgov/oscal-content/blob/main/nist.gov/SP800-53/rev5/json/NIST_SP-800-53_rev5_catalog.json>
- NIST2：CSRC Glossary、Separation of Duty（SP 800-192 の定義）。<https://csrc.nist.gov/glossary/term/separation_of_duty>
- ASVS1：OWASP ASVS 5.0.0 の V2.3（Business Logic Security）と V8（Authorization）。<https://github.com/OWASP/ASVS/blob/master/5.0/en/0x11-V2-Validation-and-Business-Logic.md>、<https://github.com/OWASP/ASVS/blob/master/5.0/en/0x17-V8-Authorization.md>
- OWASP1：OWASP Authorization Cheat Sheet（`separation`、`segregat`、`duties` の語なし）。<https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html>
- GH1：Managing environments for deployment（「Creating an environment」「Deleting an environment」）。<https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/managing-environments-for-deployment>
- GH2：Deployments and environments（「Required reviewers」「Allow administrators to bypass configured protection rules」）。<https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments>
- GH3：REST API、Workflow runs（「Get pending deployments for a workflow run」「Review pending deployments for a workflow run」）。<https://docs.github.com/en/rest/actions/workflow-runs>
- GH4：Reviewing deployments（「Approving or rejecting a job」「Bypassing deployment protection rules」）。<https://docs.github.com/en/actions/managing-workflow-runs-and-deployments/managing-deployments/reviewing-deployments>
- GH5：REST API、Deployment environments（「Create or update an environment」の `prevent_self_review` と `reviewers`）。<https://docs.github.com/en/rest/deployments/environments>
- SF1：Metadata API、ApprovalProcess。<https://developer.salesforce.com/docs/atlas.en-us.api_meta.meta/api_meta/meta_approvalprocess.htm>
- SF2：Object Reference、ProcessInstanceStep。<https://developer.salesforce.com/docs/atlas.en-us.object_reference.meta/object_reference/sforce_api_objects_processinstancestep.htm>
- SF3：REST API、Submit a Record for Approval。<https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/dome_process_approvals_submit.htm>
- SF4：REST API、Approve a Record。<https://developer.salesforce.com/docs/atlas.en-us.api_rest.meta/api_rest/dome_process_approvals_approve.htm>
- SF5：Object Reference、ProcessInstanceWorkitem。<https://developer.salesforce.com/docs/atlas.en-us.object_reference.meta/object_reference/sforce_api_objects_processinstanceworkitem.htm>
- SN1：Approval engines。<https://www.servicenow.com/docs/r/servicenow-platform/approvals/c_ApprovalEngines.html>
- SN2：Set automatic approval rules（approval rule が承認の状態を自動で Approved にできる）。<https://www.servicenow.com/docs/r/servicenow-platform/approvals/r_SetAutomaticApprovalRules.html>
- SN3：Classic approvals。<https://www.servicenow.com/docs/r/servicenow-platform/approvals/r_Approvals.html>
- SN4：Delegate approvals and tasks to another user。<https://www.servicenow.com/docs/r/servicenow-platform/self-service/t_DelegateApprovalsTasks.html>
- SN5：Ask for Approval action。<https://www.servicenow.com/docs/r/build-workflows/workflow-studio/ask-approval-flow-designer.html>
- SN-AAM1：Components installed with Advanced Approval Management。<https://www.servicenow.com/docs/r/order-management/components-installed-advanced-approval-management-for-sales.html>
- SN-AAM2：Create an approval configuration。<https://www.servicenow.com/docs/r/order-management/create-approval-configuration.html>
- SN-AAM4：Create conditions that trigger approval workflows、Create approval rules、Create Approval Rule form。<https://www.servicenow.com/docs/r/order-management/set-approval-trigger-conditions.html>、<https://www.servicenow.com/docs/r/order-management/create-approval-rules.html>、<https://www.servicenow.com/docs/r/order-management/create-approval-rule-form.html>
- SN-AAM5：Define an approval user。<https://www.servicenow.com/docs/r/order-management/create-approval-users.html>
- SN-AAM6：Submit a quote for approval。<https://www.servicenow.com/docs/r/order-management/submit-quote-for-approval-process.html>
- SN-AAM7：Configure granular delegation rules for an approver。<https://www.servicenow.com/docs/r/order-management/configure-approval-delegation.html>
- SN-AAM8：Recall a quote approval request。<https://www.servicenow.com/docs/r/order-management/recall-a-quote-approval-request.html>
- SN-AAM9：Override an approver。<https://www.servicenow.com/docs/r/order-management/override-approval-step.html>
- SN-AAM10：Approve an approval request for a quote。<https://www.servicenow.com/docs/r/order-management/approve-a-quote-approval-request.html>
- SN-AAM11：Tracking approval status and history。<https://www.servicenow.com/docs/r/order-management/tracking-approval-status.html>
- MS1：Configure approval steps in a workflow（「Assign the approval step」「Specify when the approval step is required」「Specify what happens when the document is overdue」）、Configure approval process workflow の「Prevent the submitter from approving steps in the workflow」。原文は `MicrosoftDocs/dynamics-365-unified-operations-public` の `articles/fin-ops-core/fin-ops/organization-administration/configure-approval-step-workflow.md` と `configure-approval-process-workflow.md`（`ms.date` 2026-03-10）。
- MS2：Workflow FAQ の「Can the submitter of a workflow approve the workflow?」。同じ repository の `workflow-FAQ.md`。
- MS3：Get started with Power Automate approvals（「Assign approvals to any user in your tenant」）。<https://learn.microsoft.com/en-us/power-automate/get-started-approvals>
- MS4：Delegate work items in a workflow。同じ repository の `tasks/delegate-work-items-workflow.md`。
- MS5：Workflow system architecture の表（手順 2）。同じ repository の `dev-itpro/organization-administration/workflow-system-architecture.md`。
- MS6：Create a workflow の「Save and activate the workflow」。同じ repository の `create-workflow.md`。
- MS7：Purchase requisition workflow。同じ repository の `articles/supply-chain/procurement/purchase-requisitions-workflow.md`。
- SAP1：Release Strategy、Release Conditions、Release Prerequisites（Sourcing and Procurement、2025 FPS02）。`help.sap.com` の検索 API の概要文。[概要文のみ]
- SAP2：Release Code（同上）。<https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/af9ef57f504840d2b81be8667206d485/4480b65334e6b54ce10000000a174cb4.html> [概要文のみ]
- SAP3：Manage Workflows for Purchase Requisitions。<https://help.sap.com/docs/SAP_S4HANA_ON-PREMISE/af9ef57f504840d2b81be8667206d485/5634d5e7a337449c989b632e98f84abf.html> [概要文のみ]
- WD1：Concept: Approval Step。<https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/business-process-step-types/dan1370797855296.html>
- WD2：Create Business Process Condition Rules。<https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/customize-business-processes/dan1370796347209.html>
- WD3：WD1 の「Send Back」の段落（routing restriction と `Not Required`）。
- WD4：Reference: Actions Available on Business Processes。<https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/business-process-framework-concepts/mee1553714361673.html>
- WD5：Example: Delegate Specific Business Processes。<https://doc.workday.com/admin-guide/en-us/manage-workday/business-processes/delegate-business-processes/business-process-task-delegation/example-delegate-specific-business-processes.html>
- JI1：What are approvals?、Enforce an approval step。<https://support.atlassian.com/jira-service-management-cloud/docs/what-are-approvals/>、<https://support.atlassian.com/jira-service-management-cloud/docs/enforce-an-approval-step/>
- JI2：Add an approval step to a workflow。<https://support.atlassian.com/jira-service-management-cloud/docs/add-an-approval-to-a-workflow/>
- JI3：Define approvers for a request type、Allow customers to choose approvers。<https://support.atlassian.com/jira-service-management-cloud/docs/set-up-a-pre-set-list-of-approvers/>、<https://support.atlassian.com/jira-service-management-cloud/docs/allow-customers-to-choose-approvers/>
- NS1：Supervisors, Approvers, and Approval Limits。<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2395573.html>
- NS2：Creating Your First Approval Rule。<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_90132659089.html>
- NS3：Approving a Purchase Request。<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2395820.html>
- NS4：Requisition Approval Workflow States。<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_4109347847.html>
- NS5：Using the Approval Routing Feature の節（Approval Routing の概要と設定の手順）。<https://docs.oracle.com/en/cloud/saas/netsuite/ns-online-help/section_N2395258.html>（この URL は検索結果の `netsuitedocumentation1.gitlab.io` の写しにも出る。本文は Oracle の URL で読んだ）
- ZU1：Advanced Approval in Zuora CPQ。<https://docs.zuora.com/en-us/zuora-cpq/manage-subscriptions/advanced-cpq-x-functionalities/advanced-approval-in-zuora-cpq>
- ZU2：Create an approval flow、Create an approval rule、Create an approval chain（同じ文書の下位のページ）。
- ZU3：Submit a quote for approval。<https://docs.zuora.com/en-us/zuora-cpq/manage-subscriptions/advanced-cpq-x-functionalities/advanced-approval-in-zuora-cpq/submit-a-quote-for-approval>
- ZU4：Review and approve an approval request in Quote Studio。<https://docs.zuora.com/en-us/zuora-cpq/manage-subscriptions/advanced-cpq-x-functionalities/advanced-approval-in-zuora-cpq/review-and-approve-an-approval-request-in-quote-studio>
- CP1：Coupa Success Portal、Self Approval。<https://success.coupa.com/Implement/Best_Practices/Procurement_Design_Considerations/Self_Approval> [概要文のみ]
- CP2：Coupa Success Portal、Managerial Hierarchy。<https://success.coupa.com/Implement/Best_Practices/Invoicing_Design_Considerations/Managerial_Hierarchy>、リリースノート 17.7。<https://success.coupa.com/Support/Releases/Old/17/Maintenance_Updates/17.7> [概要文のみ]
- AR1：Ariba サポートの KB0408276（Auto-Approve if Quantity or Amount Has Not Increased）。<https://support.ariba.com/item/view/KB0408276> [概要文のみ]
- AR2：SAP Learning、Setting Approval Parameters（Intelligent Configuration Manager）。<https://learning.sap.com/courses/sap-ariba-procurement-configuring-approval-rules/setting-approval-parameters_bf1a57e7-d5d6-4f1b-b983-dd10c21106c1> [概要文のみ]
- KT1：kintone ヘルプ、プロセス管理。<https://jp.cybozu.help/k/ja/user/app_settings/process.html>
- KT2：kintone ヘルプ、ステータス、作業者、アクションとは、作業者の設定。<https://jp.cybozu.help/k/ja/app/process/requiredsettings_process>、<https://jp.cybozu.help/k/ja/app/process/set_assignee>
- KT3：kintone ヘルプ、承認者を自動入力する設定例。<https://jp.cybozu.help/k/ja/app/process/assign_manager>
- KT4：kintone ヘルプ、アクションが実行できる条件の設定。<https://jp.cybozu.help/k/ja/app/process/set_action_conditions>
- KT5：kintone ヘルプ、作業者以外でも実行できるアクションの設定、プロセス管理を設定したアプリの使いかた。<https://jp.cybozu.help/k/ja/app/process/set_non_assignee_actions>、<https://jp.cybozu.help/k/ja/app/process/process_app_operations>
- KT6：kintone ヘルプ、プロセス管理の設定変更時の注意点。<https://jp.cybozu.help/k/ja/app/process/change_processsettings>
- KT8：cybozu developer network、プロセス管理の設定を取得する。<https://cybozu.dev/ja/kintone/docs/rest-api/apps/settings/get-process-management-settings/>
- KT9：cybozu developer network、実行可能なプロセス管理のアクションの一覧を取得する。<https://cybozu.dev/ja/kintone/docs/js-api/app/get-status-actions/>
- KT10：kintone ヘルプ、プロセス管理と組み合わせると便利な設定（状態ごとのレコードのアクセス権）。<https://jp.cybozu.help/k/ja/app/process/process_tips>
- FR3：freee ヘルプセンター、申請経路の金額分岐を利用する。<https://support.freee.co.jp/hc/ja/articles/38972823684505> [概要文のみ]
- FR1：freee ヘルプセンター、承認ルートについて。<https://support.freee.co.jp/hc/ja/articles/28464661763865>
- FR2：freee ヘルプセンター、freee販売で申請・承認機能および申請経路の設定をする。<https://support.freee.co.jp/hc/ja/articles/19753608962841>
- JC1：ジョブカン経費精算/WF、自分の承認をスキップできますか？<https://jobcanwf.zendesk.com/hc/ja/articles/115002227967>
- JC2：ジョブカン経費精算/WF、代理承認する。<https://jobcanwf.zendesk.com/hc/ja/articles/213820297>
- JC3：ジョブカン経費精算/WF、柔軟な経路設定を試してみましょう。<https://jobcanwf.zendesk.com/hc/ja/articles/220005108>
- MF1：Money Forward クラウド債務支払、「申請者本人による承認可否」と「同一承認者の複数回承認を不要とする」設定が行えるようになりました（2021-07-09）。<https://biz.moneyforward.com/support/payable/news/new-feature/20210709.html> [概要文のみ。題名だけ]
- XP1：X-point Cloud、柔軟な承認ルート設定機能。<https://www.atled.jp/xpoint_cloud/functions/flexible_authorization_flow/>
