package com.example.demo.authorization;

import com.example.demo.testkit.OpenApiOperations;
import org.springframework.http.HttpMethod;

/**
 * 認可の表の行。commit した {@code openapi/openapi.yaml} の operation と 1 対 1 に対応する（ADR-129）。
 *
 * <p>定数を足すと、{@link AuthorizationExpectations} と {@link AuthorizationRequests#build} の {@code
 * switch} 式がコンパイルに失敗する。spec との一致は {@link AuthorizationMatrixTest} が確かめる。
 */
enum AuthorizationOperation {
  FIND_CURRENT_TENANT(HttpMethod.GET, "/api/current-tenant"),
  SELECT_TENANT(HttpMethod.PUT, "/api/current-tenant"),
  LIST_TENANT_MEMBERS(HttpMethod.GET, "/api/current-tenant/members"),
  REMOVE_TENANT_MEMBER(HttpMethod.DELETE, "/api/current-tenant/members/{userId}"),
  CHANGE_TENANT_MEMBER_ROLE(HttpMethod.PUT, "/api/current-tenant/members/{userId}"),
  LIST_MEMBERSHIPS(HttpMethod.GET, "/api/memberships"),
  LIST_ORDERS(HttpMethod.GET, "/api/orders"),
  DRAFT_ORDER(HttpMethod.POST, "/api/orders"),
  FIND_ORDER(HttpMethod.GET, "/api/orders/{orderId}"),
  CANCEL_ORDER(HttpMethod.POST, "/api/orders/{orderId}/cancel"),
  CONFIRM_ORDER(HttpMethod.POST, "/api/orders/{orderId}/confirm"),
  CHANGE_ORDER_LINES(HttpMethod.PUT, "/api/orders/{orderId}/lines"),
  LIST_PAYMENTS(HttpMethod.GET, "/api/payments"),
  LIST_PRODUCTS(HttpMethod.GET, "/api/products");

  /** HTTP メソッド。 */
  // HttpMethod は名前だけを持つ不変の値だが、Error Prone の @Immutable を付けていない。
  @SuppressWarnings("ImmutableEnumChecker")
  private final HttpMethod httpMethod;

  /** spec のパスのテンプレート。 */
  private final String pathTemplate;

  AuthorizationOperation(final HttpMethod method, final String path) {
    this.httpMethod = method;
    this.pathTemplate = path;
  }

  /** HTTP メソッド。 */
  /* package */ HttpMethod method() {
    return httpMethod;
  }

  /** spec のパスのテンプレート。 */
  /* package */ String path() {
    return pathTemplate;
  }

  /** spec の operation と突き合わせるキー（{@link OpenApiOperations#key}）。 */
  /* package */ String key() {
    return OpenApiOperations.key(httpMethod.name(), pathTemplate);
  }

  /** 業務の行を書き換えうる操作か。GET 以外を書き込みとみなす。 */
  /* package */ boolean writes() {
    return !HttpMethod.GET.equals(httpMethod);
  }
}
