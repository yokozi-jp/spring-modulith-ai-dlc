package com.example.demo;

import static com.example.demo.AuthorizationOutcome.ALLOWED;
import static com.example.demo.AuthorizationOutcome.FORBIDDEN;
import static com.example.demo.AuthorizationOutcome.HIDDEN;
import static com.example.demo.AuthorizationOutcome.UNPROCESSABLE;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 認可の表の期待と、期待と違ってよい組の一覧（ADR-129）。
 *
 * <p>期待は docs の規約から手で書き、本番の方針から作らない。見えなければ 404 か存在しないときと同じ応答、見えるが許さなければ 403
 * （docs/web-api/status-codes.md）、所属の管理の API は ADMIN
 * だけ（docs/web-api/authentication-and-session.md）、注文と決済記録は持ち主だけ（ADR-129）、
 * 業務の行は別のテナントから読めない（ADR-107）。
 */
final class AuthorizationExpectations {

  /** #188 が入るまで、同じテナントの別の利用者が注文に触れる理由。 */
  private static final String ORDER_NOT_OWNER_SCOPED =
      "注文の Repository と OrderQueries は持ち主で絞らず、同じテナントの注文をすべて読み書きできる。"
          + "admin に持ち主でない注文を見せるかも #188 で決める";

  /** #188 が入るまで、同じテナントの別の利用者が決済記録を読める理由。 */
  private static final String PAYMENT_NOT_OWNER_SCOPED =
      "PaymentQueries は注文の持ち主を確かめず、同じテナントの決済記録をすべて読める。" + "admin に持ち主でない注文の決済記録を見せるかも #188 で決める";

  /** 期待と違ってよい組。理由の Issue で直したら、その組を消す。 */
  /* package */ static final List<AuthorizationExemption> EXEMPTIONS =
      Stream.of(
              AuthorizationOperation.LIST_ORDERS,
              AuthorizationOperation.FIND_ORDER,
              AuthorizationOperation.CANCEL_ORDER,
              AuthorizationOperation.CONFIRM_ORDER,
              AuthorizationOperation.CHANGE_ORDER_LINES,
              AuthorizationOperation.LIST_PAYMENTS)
          .flatMap(operation ->
              Stream.of(AuthorizationActor.SAME_TENANT_MEMBER, AuthorizationActor.SAME_TENANT_ADMIN)
                  .map(actor ->
                      new AuthorizationExemption(
                          operation,
                          actor,
                          operation == AuthorizationOperation.LIST_PAYMENTS
                              ? PAYMENT_NOT_OWNER_SCOPED
                              : ORDER_NOT_OWNER_SCOPED,
                          188)))
          .toList();

  private AuthorizationExpectations() {}

  /** 操作と操作する人の組に期待する結果。 */
  // 期待の表は、操作ごとの case を持ち default のない switch 式で書く（ADR-129）。case の数だけ複雑度が上がる。
  @SuppressWarnings("PMD.CyclomaticComplexity")
  /* package */ static AuthorizationOutcome expected(
      final AuthorizationOperation operation, final AuthorizationActor actor) {
    final Row row =
        switch (operation) {
          // 自分の所属とテナントだけを返す。所属の行は利用者の ID で引く。
          case FIND_CURRENT_TENANT, LIST_MEMBERSHIPS -> new Row(ALLOWED, ALLOWED, ALLOWED, ALLOWED);
          // 所属していないテナントを選ぶと 422（openapi.yaml の selectTenant）。
          case SELECT_TENANT -> new Row(ALLOWED, ALLOWED, ALLOWED, UNPROCESSABLE);
          // 所属の管理は ADMIN だけ。別のテナントの人も MEMBER なので、対象が見えるかより先に 403 になる。
          case LIST_TENANT_MEMBERS, REMOVE_TENANT_MEMBER, CHANGE_TENANT_MEMBER_ROLE ->
              new Row(FORBIDDEN, FORBIDDEN, ALLOWED, FORBIDDEN);
          // 別のテナントの商品は見えず、存在しない商品と同じ 422（openapi.yaml の draftOrder）。
          case DRAFT_ORDER -> new Row(ALLOWED, ALLOWED, ALLOWED, UNPROCESSABLE);
          // 注文と決済記録は持ち主だけに見える（ADR-129 の見える範囲）。
          case LIST_ORDERS,
              FIND_ORDER,
              CANCEL_ORDER,
              CONFIRM_ORDER,
              CHANGE_ORDER_LINES,
              LIST_PAYMENTS ->
              new Row(ALLOWED, HIDDEN, HIDDEN, HIDDEN);
          // 商品はテナントの全員に見える。
          case LIST_PRODUCTS -> new Row(ALLOWED, ALLOWED, ALLOWED, HIDDEN);
        };
    return row.outcomeOf(actor);
  }

  /** 組が期待と違ってよい理由。なければ空。 */
  /* package */ static Optional<AuthorizationExemption> exemptionOf(
      final AuthorizationOperation operation, final AuthorizationActor actor) {
    return EXEMPTIONS.stream()
        .filter(exemption -> exemption.operation() == operation && exemption.actor() == actor)
        .findFirst();
  }

  /** 1 つの操作の、操作する人ごとの期待。操作する人を足すと {@link #outcomeOf} がコンパイルに失敗する。 */
  private record Row(
      AuthorizationOutcome owner,
      AuthorizationOutcome sameTenantMember,
      AuthorizationOutcome sameTenantAdmin,
      AuthorizationOutcome otherTenantMember) {

    private AuthorizationOutcome outcomeOf(final AuthorizationActor actor) {
      return switch (actor) {
        case OWNER -> owner;
        case SAME_TENANT_MEMBER -> sameTenantMember;
        case SAME_TENANT_ADMIN -> sameTenantAdmin;
        case OTHER_TENANT_MEMBER -> otherTenantMember;
      };
    }
  }

  /**
   * 期待と違ってよい組。
   *
   * @param operation 操作
   * @param actor 操作する人
   * @param reason 期待と違う理由
   * @param issue 直す Issue の番号
   */
  /* package */ record AuthorizationExemption(
      AuthorizationOperation operation, AuthorizationActor actor, String reason, int issue) {}
}
