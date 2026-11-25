package com.example.demo.authorization;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.example.demo.testkit.UniqueCodes;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.json.JsonMapper;

/** 認可の表の操作ごとに、対象を指す MockMvc の要求を作る。ログインと CSRF のトークンは呼ぶ側が付ける。 */
final class AuthorizationRequests {

  /** 対象の注文とテナントのロック番号。対象は作ったばかりで、変更されていない。 */
  private static final long LOCK_NO = 1L;

  /** ロック番号を送る本文の項目と query の引数の名前。 */
  private static final String LOCK_NO_NAME = "lockNo";

  /** 要求の本文を書く。 */
  private static final JsonMapper MAPPER = JsonMapper.builder().build();

  private AuthorizationRequests() {}

  /** 操作の、対象を指す要求を作る。 */
  // 操作ごとの case を持ち default のない switch 式で書き、操作を足すとコンパイルに失敗させる。case の数だけ複雑度が上がる。
  @SuppressWarnings("PMD.CyclomaticComplexity")
  /* package */ static MockHttpServletRequestBuilder build(
      final AuthorizationOperation operation, final Targets targets) {
    return switch (operation) {
      case FIND_CURRENT_TENANT, LIST_TENANT_MEMBERS, LIST_MEMBERSHIPS, LIST_ORDERS, LIST_PRODUCTS ->
          request(operation.method(), operation.path());
      case SELECT_TENANT -> json(operation, Map.of("tenantId", targets.tenantId()));
      case REMOVE_TENANT_MEMBER ->
          request(operation.method(), operation.path(), targets.memberUserId())
              .param(LOCK_NO_NAME, String.valueOf(LOCK_NO));
      case CHANGE_TENANT_MEMBER_ROLE ->
          json(operation, Map.of("role", "ADMIN", LOCK_NO_NAME, LOCK_NO), targets.memberUserId());
      case DRAFT_ORDER -> draftOrder(targets.productId());
      case FIND_ORDER -> request(operation.method(), operation.path(), targets.orderId());
      case CANCEL_ORDER, CONFIRM_ORDER ->
          json(operation, Map.of(LOCK_NO_NAME, LOCK_NO), targets.orderId());
      case CHANGE_ORDER_LINES ->
          json(
              operation,
              Map.of("lines", lines(targets.productId()), LOCK_NO_NAME, LOCK_NO),
              targets.orderId());
      case LIST_PAYMENTS ->
          request(operation.method(), operation.path())
              .param("orderId", targets.orderId().toString());
    };
  }

  /** 商品を 2 個注文する下書きを作る要求。 */
  /* package */ static MockHttpServletRequestBuilder draftOrder(final UUID productId) {
    return json(
        AuthorizationOperation.DRAFT_ORDER,
        Map.of("customerOrderCode", UniqueCodes.next("C"), "lines", lines(productId)));
  }

  private static List<Map<String, Object>> lines(final UUID productId) {
    return List.of(Map.of("productId", productId, "quantity", 2));
  }

  private static MockHttpServletRequestBuilder json(
      final AuthorizationOperation operation,
      final Map<String, ?> body,
      final Object... pathVariables) {
    return request(operation.method(), operation.path(), pathVariables)
        .contentType(MediaType.APPLICATION_JSON)
        .content(MAPPER.writeValueAsString(body));
  }

  /**
   * 要求が指す対象。
   *
   * @param orderId 持ち主が作った注文。パスか query で指す
   * @param productId 持ち主のテナントの販売中の商品。本文で指す
   * @param memberUserId 所属の操作の対象の利用者。持ち主のテナントの持ち主で、パスで指す
   * @param tenantId 持ち主のテナント。テナントを選ぶ本文で指す
   */
  /* package */ record Targets(UUID orderId, UUID productId, String memberUserId, UUID tenantId) {

    /** 注文と商品を作る前の要求が指す対象。注文と商品はまだないので、存在しない id にする。 */
    /* package */ static Targets unseeded(final String memberUserId, final UUID tenantId) {
      return new Targets(UUID.randomUUID(), UUID.randomUUID(), memberUserId, tenantId);
    }

    /** パスと query の id（注文と利用者）だけを、存在しない id に替える。本文の値は替えない。 */
    /* package */ Targets absent() {
      return new Targets(UUID.randomUUID(), productId, UUID.randomUUID().toString(), tenantId);
    }
  }
}
