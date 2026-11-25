package com.example.demo;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.demo.testkit.UniqueCodes;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** 認可の表の操作ごとに、対象を指す MockMvc の要求を作る。ログインと CSRF のトークンは呼ぶ側が付ける。 */
final class AuthorizationRequests {

  /** 対象の注文とテナントのロック番号。対象は作ったばかりで、変更されていない。 */
  private static final String LOCK_NO = "1";

  private AuthorizationRequests() {}

  /** 操作の、対象を指す要求を作る。 */
  // 操作ごとの case を持ち default のない switch 式で書き、操作を足すとコンパイルに失敗させる。case の数だけ複雑度が上がる。
  @SuppressWarnings("PMD.CyclomaticComplexity")
  /* package */ static MockHttpServletRequestBuilder build(
      final AuthorizationOperation operation, final Targets targets) {
    final String path = operation.path();
    return switch (operation) {
      case FIND_CURRENT_TENANT, LIST_TENANT_MEMBERS, LIST_MEMBERSHIPS, LIST_ORDERS, LIST_PRODUCTS ->
          request(operation.method(), path);
      case SELECT_TENANT -> json(operation, path, "{\"tenantId\":\"" + targets.tenantId() + "\"}");
      case REMOVE_TENANT_MEMBER ->
          request(operation.method(), path, targets.memberUserId()).param("lockNo", LOCK_NO);
      case CHANGE_TENANT_MEMBER_ROLE ->
          json(
              operation,
              path,
              "{\"role\":\"ADMIN\",\"lockNo\":" + LOCK_NO + "}",
              targets.memberUserId());
      case DRAFT_ORDER ->
          json(
              operation,
              path,
              "{\"customerOrderCode\":\""
                  + UniqueCodes.next("C")
                  + "\",\"lines\":"
                  + lines(targets.productId())
                  + "}");
      case FIND_ORDER -> request(operation.method(), path, targets.orderId());
      case CANCEL_ORDER, CONFIRM_ORDER ->
          json(operation, path, "{\"lockNo\":" + LOCK_NO + "}", targets.orderId());
      case CHANGE_ORDER_LINES ->
          json(
              operation,
              path,
              "{\"lines\":" + lines(targets.productId()) + ",\"lockNo\":" + LOCK_NO + "}",
              targets.orderId());
      case LIST_PAYMENTS ->
          request(operation.method(), path).param("orderId", targets.orderId().toString());
    };
  }

  private static String lines(final UUID productId) {
    return "[{\"productId\":\"" + productId + "\",\"quantity\":2}]";
  }

  private static MockHttpServletRequestBuilder json(
      final AuthorizationOperation operation,
      final String path,
      final String body,
      final Object... pathVariables) {
    return request(operation.method(), path, pathVariables)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  /**
   * 要求が指す対象。
   *
   * @param orderId 持ち主が作った注文
   * @param productId 持ち主のテナントの販売中の商品。本文で指定する
   * @param memberUserId 所属の操作の対象の利用者（持ち主）
   * @param tenantId 持ち主のテナント。テナントを選ぶ本文で指定する
   */
  /* package */ record Targets(UUID orderId, UUID productId, String memberUserId, UUID tenantId) {

    /** 対象を作る前の要求が指す対象。注文と商品はまだないので、存在しない id にする。 */
    /* package */ static Targets unseeded(final String memberUserId, final UUID tenantId) {
      return new Targets(UUID.randomUUID(), UUID.randomUUID(), memberUserId, tenantId);
    }

    /** パスと query の id（注文と利用者）だけを、存在しない id に替える。本文の値は替えない。 */
    /* package */ Targets absent() {
      return new Targets(UUID.randomUUID(), productId, UUID.randomUUID().toString(), tenantId);
    }
  }
}
