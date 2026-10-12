package com.example.demo.testkit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * MockMvc のテストメソッドが確かめる operation と HTTP status の組を宣言する。
 *
 * <p>{@code ApiContractCoverageTest} が、コミット済みの {@code openapi/openapi.yaml} の operation と応答の status
 * の組ごとに、この宣言があることを検査する（ADR-077）。宣言は、そのテストが実際にその status を期待していることを表す。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Repeatable(ContractCase.List.class)
public @interface ContractCase {

  /**
   * HTTP メソッド。
   *
   * @return HTTP メソッド
   */
  RequestMethod method();

  /**
   * OpenAPI の path の形（{@code /api/orders/{orderId}}）。
   *
   * @return path の形
   */
  String path();

  /**
   * テストが期待する HTTP status。
   *
   * @return HTTP status
   */
  int[] status();

  /** 一つのテストメソッドに付けた複数の {@link ContractCase}。 */
  @Target(ElementType.METHOD)
  @Retention(RetentionPolicy.RUNTIME)
  @interface List {

    /**
     * 付けた宣言。
     *
     * @return 付けた宣言
     */
    ContractCase[] value();
  }
}
