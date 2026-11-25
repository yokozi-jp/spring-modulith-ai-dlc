package com.example.demo;

import org.springframework.http.HttpMethod;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 認可の表の行。commit した {@code openapi/openapi.yaml} の operation と 1 対 1 に対応する（ADR-129）。
 *
 * <p>定数を足すと、{@link AuthorizationExpectations#expected} と {@link AuthorizationRequests#build} の
 * {@code switch} 式がコンパイルに失敗する。spec との一致は {@link AuthorizationMatrixTest} が確かめる。
 */
enum AuthorizationOperation {
  FIND_CURRENT_TENANT(RequestMethod.GET, "/api/current-tenant"),
  SELECT_TENANT(RequestMethod.PUT, "/api/current-tenant"),
  LIST_TENANT_MEMBERS(RequestMethod.GET, "/api/current-tenant/members"),
  REMOVE_TENANT_MEMBER(RequestMethod.DELETE, "/api/current-tenant/members/{userId}"),
  CHANGE_TENANT_MEMBER_ROLE(RequestMethod.PUT, "/api/current-tenant/members/{userId}"),
  LIST_MEMBERSHIPS(RequestMethod.GET, "/api/memberships"),
  LIST_ORDERS(RequestMethod.GET, "/api/orders"),
  DRAFT_ORDER(RequestMethod.POST, "/api/orders"),
  FIND_ORDER(RequestMethod.GET, "/api/orders/{orderId}"),
  CANCEL_ORDER(RequestMethod.POST, "/api/orders/{orderId}/cancel"),
  CONFIRM_ORDER(RequestMethod.POST, "/api/orders/{orderId}/confirm"),
  CHANGE_ORDER_LINES(RequestMethod.PUT, "/api/orders/{orderId}/lines"),
  LIST_PAYMENTS(RequestMethod.GET, "/api/payments"),
  LIST_PRODUCTS(RequestMethod.GET, "/api/products");

  /** HTTP メソッド。 */
  private final RequestMethod requestMethod;

  /** spec のパスのテンプレート。 */
  private final String pathTemplate;

  AuthorizationOperation(final RequestMethod method, final String path) {
    this.requestMethod = method;
    this.pathTemplate = path;
  }

  /** HTTP メソッド。 */
  /* package */ HttpMethod method() {
    return requestMethod.asHttpMethod();
  }

  /** spec のパスのテンプレート。 */
  /* package */ String path() {
    return pathTemplate;
  }

  /** spec の operation と突き合わせるキー（「メソッド パス」）。 */
  /* package */ String key() {
    return requestMethod.name() + " " + pathTemplate;
  }

  /** 業務の行を書き換えうる操作か。GET 以外を書き込みとみなす。 */
  /* package */ boolean writes() {
    return requestMethod != RequestMethod.GET;
  }
}
