package com.example.demo.authorization;

import static com.example.demo.authorization.AuthorizationOutcome.ALLOWED;
import static com.example.demo.authorization.AuthorizationOutcome.FORBIDDEN;
import static com.example.demo.authorization.AuthorizationOutcome.HIDDEN;
import static com.example.demo.authorization.AuthorizationOutcome.UNPROCESSABLE;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 認可の表の期待と、期待と違ってよい組の一覧（ADR-129）。
 *
 * <p>期待は docs の規約から手で書き、本番の方針から作らない。見えなければ 404 か存在しないときと同じ応答、見えるが許さなければ 403
 * （docs/web-api/status-codes.md）、所属の管理の API は選んだテナントの ADMIN だけで、別のテナントの所属は見えない
 * （docs/web-api/authentication-and-session.md）、注文と決済記録は Repository が利用者の ID で絞る（ADR-129）、
 * 業務の行は別のテナントから読めない（ADR-107）。
 *
 * <p>同じテナントの admin に持ち主でない注文と決済記録を見せるかは、ADR-129 が #188 に預けている。表は持ち主だけに見える形を、#188
 * が決めるまでの意図した期待として書く。admin の列は #188 の決定に合わせて直す。
 */
final class AuthorizationExpectations {

  /** #188 が入るまで、同じテナントの別の利用者が注文に触れる理由。 */
  private static final String ORDER_NOT_OWNER_SCOPED =
      "注文の Repository と OrderQueries は持ち主で絞らず、同じテナントの注文をすべて読み書きできる。"
          + "admin に持ち主でない注文を見せるかも #188 で決める";

  /** #188 が入るまで、同じテナントの別の利用者が決済記録を読める理由。 */
  private static final String PAYMENT_NOT_OWNER_SCOPED =
      "PaymentQueries は注文の持ち主を確かめず、同じテナントの決済記録をすべて読める。" + "admin に持ち主でない注文の決済記録を見せるかも #188 で決める";

  /** 持ち主の注文と決済記録を扱う Issue。 */
  private static final int OWNER_SCOPE_ISSUE = 188;

  /** 期待と違ってよい組。理由の Issue で直すと今の結果が変わって失敗するので、その組を消す。 */
  /* package */ static final List<AuthorizationExemption> EXEMPTIONS =
      Stream.concat(
              allowedToSameTenantNonOwners(
                  OWNER_SCOPE_ISSUE,
                  ORDER_NOT_OWNER_SCOPED,
                  AuthorizationOperation.LIST_ORDERS,
                  AuthorizationOperation.FIND_ORDER,
                  AuthorizationOperation.CANCEL_ORDER,
                  AuthorizationOperation.CONFIRM_ORDER,
                  AuthorizationOperation.CHANGE_ORDER_LINES),
              allowedToSameTenantNonOwners(
                  OWNER_SCOPE_ISSUE,
                  PAYMENT_NOT_OWNER_SCOPED,
                  AuthorizationOperation.LIST_PAYMENTS))
          .toList();

  private AuthorizationExpectations() {}

  /** 操作と操作する人の組に期待する結果。 */
  /* package */ static AuthorizationOutcome expected(
      final AuthorizationOperation operation, final AuthorizationActor actor) {
    return row(operation).outcomeOf(actor);
  }

  /** 操作を許した組の本文が見せるもの。 */
  /* package */ static Shown shown(final AuthorizationOperation operation) {
    return row(operation).shown();
  }

  /** 組が期待と違ってよい理由と今の結果。なければ空。 */
  /* package */ static Optional<AuthorizationExemption> exemptionOf(
      final AuthorizationOperation operation, final AuthorizationActor actor) {
    return EXEMPTIONS.stream()
        .filter(exemption -> exemption.operation() == operation && exemption.actor() == actor)
        .findFirst();
  }

  /** 組を判定する結果。例外の一覧にある組は今の結果、ほかは期待する結果である。 */
  /* package */ static AuthorizationOutcome judged(
      final AuthorizationOperation operation, final AuthorizationActor actor) {
    return exemptionOf(operation, actor)
        .map(AuthorizationExemption::current)
        .orElseGet(() -> expected(operation, actor));
  }

  // 期待の表は、操作ごとの case を持ち default のない switch 式で書く（ADR-129）。case の数だけ複雑度が上がる。
  @SuppressWarnings("PMD.CyclomaticComplexity")
  private static Row row(final AuthorizationOperation operation) {
    return switch (operation) {
      // 自分の所属とテナントだけを返す。所属の行は利用者の ID で引く。
      case FIND_CURRENT_TENANT, LIST_MEMBERSHIPS -> Row.everyone(Shown.ACTOR_TENANT);
      // 所属していないテナントを選ぶと 422（openapi.yaml の selectTenant）。
      // 別のテナントの商品は見えず、存在しない商品と同じ 422（openapi.yaml の draftOrder）。
      case SELECT_TENANT, DRAFT_ORDER -> Row.homeTenantByBody();
      // 所属の管理は ADMIN だけで、選んだテナントの所属だけを返す。
      case LIST_TENANT_MEMBERS -> Row.ownTenantAdmins(Shown.ACTOR_TENANT_MEMBERS);
      // 別のテナントの ADMIN がパスで持ち主を指しても、別のテナントの所属は見えず 404 になる。
      case REMOVE_TENANT_MEMBER, CHANGE_TENANT_MEMBER_ROLE -> Row.homeTenantAdminsByPath();
      // 注文と決済記録は持ち主だけに見える。admin の列は #188 が決める（クラスの説明）。
      case LIST_ORDERS, FIND_ORDER -> Row.ownerOnly(Shown.TARGET_ORDER);
      case CANCEL_ORDER, CONFIRM_ORDER, CHANGE_ORDER_LINES -> Row.ownerOnly(Shown.NOTHING);
      case LIST_PAYMENTS -> Row.ownerOnly(Shown.TARGET_PAYMENT);
      // 商品はテナントの全員に見える。
      case LIST_PRODUCTS -> Row.homeTenant(Shown.TARGET_PRODUCT);
    };
  }

  /** 同じテナントの持ち主でない MEMBER と ADMIN が、操作を今は許される組を、理由ごとに作る。 */
  private static Stream<AuthorizationExemption> allowedToSameTenantNonOwners(
      final int issue, final String reason, final AuthorizationOperation... operations) {
    return Arrays.stream(operations)
        .flatMap(operation ->
            Stream.of(AuthorizationActor.SAME_TENANT_MEMBER, AuthorizationActor.SAME_TENANT_ADMIN)
                .map(
                    actor -> new AuthorizationExemption(operation, actor, ALLOWED, reason, issue)));
  }

  /** 操作を許した組の本文が見せるもの。判定は本文に ID があるかで確かめる。 */
  /* package */ enum Shown {
    /** 本文を確かめない。 */
    NOTHING,
    /** 操作する人が所属するテナントだけを見せ、もう 1 つのテナントを見せない。 */
    ACTOR_TENANT,
    /** 操作する人が所属するテナントの利用者をすべて見せ、もう 1 つのテナントの利用者を見せない。 */
    ACTOR_TENANT_MEMBERS,
    /** 持ち主が作った注文を見せる。 */
    TARGET_ORDER,
    /** 持ち主の注文の決済記録を見せる。 */
    TARGET_PAYMENT,
    /** 持ち主のテナントの商品を見せる。 */
    TARGET_PRODUCT
  }

  /**
   * 1 つの操作の、操作する人ごとの期待と、許した本文が見せるもの。
   *
   * <p>操作する人を足すと、{@link #outcomeOf} と、名前の付いた形を作るメソッドがコンパイルに失敗する。
   */
  private record Row(
      AuthorizationOutcome owner,
      AuthorizationOutcome sameTenantMember,
      AuthorizationOutcome sameTenantAdmin,
      AuthorizationOutcome otherTenantMember,
      AuthorizationOutcome otherTenantAdmin,
      Shown shown) {

    /** 誰にでも許す。 */
    private static Row everyone(final Shown shown) {
      return new Row(ALLOWED, ALLOWED, ALLOWED, ALLOWED, ALLOWED, shown);
    }

    /** 本文で持ち主のテナントのもの（テナントか商品）を指す。持ち主のテナントの人に許し、別のテナントの人には存在しないものと同じ 422 にする。 */
    private static Row homeTenantByBody() {
      return new Row(ALLOWED, ALLOWED, ALLOWED, UNPROCESSABLE, UNPROCESSABLE, Shown.NOTHING);
    }

    /** 選んだテナントの ADMIN に許し、MEMBER には 403 にする。どちらのテナントの ADMIN も自分のテナントを見る。 */
    private static Row ownTenantAdmins(final Shown shown) {
      return new Row(FORBIDDEN, FORBIDDEN, ALLOWED, FORBIDDEN, ALLOWED, shown);
    }

    /** パスで持ち主のテナントの利用者を指す。持ち主のテナントの ADMIN に許し、MEMBER には 403、別のテナントの ADMIN には見えない。 */
    private static Row homeTenantAdminsByPath() {
      return new Row(FORBIDDEN, FORBIDDEN, ALLOWED, FORBIDDEN, HIDDEN, Shown.NOTHING);
    }

    /** 持ち主にだけ見える。 */
    private static Row ownerOnly(final Shown shown) {
      return new Row(ALLOWED, HIDDEN, HIDDEN, HIDDEN, HIDDEN, shown);
    }

    /** 持ち主のテナントの人に見え、別のテナントの人には見えない。 */
    private static Row homeTenant(final Shown shown) {
      return new Row(ALLOWED, ALLOWED, ALLOWED, HIDDEN, HIDDEN, shown);
    }

    private AuthorizationOutcome outcomeOf(final AuthorizationActor actor) {
      return switch (actor) {
        case OWNER -> owner;
        case SAME_TENANT_MEMBER -> sameTenantMember;
        case SAME_TENANT_ADMIN -> sameTenantAdmin;
        case OTHER_TENANT_MEMBER -> otherTenantMember;
        case OTHER_TENANT_ADMIN -> otherTenantAdmin;
      };
    }
  }

  /**
   * 期待と違ってよい組。
   *
   * @param operation 操作
   * @param actor 操作する人
   * @param current 今の結果。組はこの結果で判定し、理由の Issue で直って結果が変わると失敗する
   * @param reason 期待と違う理由
   * @param issue 直す Issue の番号
   */
  /* package */ record AuthorizationExemption(
      AuthorizationOperation operation,
      AuthorizationActor actor,
      AuthorizationOutcome current,
      String reason,
      int issue) {}
}
