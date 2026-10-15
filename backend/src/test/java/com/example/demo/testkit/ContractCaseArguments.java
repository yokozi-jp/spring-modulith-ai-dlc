package com.example.demo.testkit;

import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

/**
 * テストメソッドに付けた {@link ContractCase} を、{@code (method, path)} の引数として返す。
 *
 * <p>送る要求と宣言を一か所に書くための提供元で、{@code @ArgumentsSource} で使う。 path の {@code {変数}} は固定の UUID に置き換える。
 */
public final class ContractCaseArguments implements ArgumentsProvider {

  /** path の変数に入れる UUID。 */
  private static final String ID = "0b6c8f6e-2f4a-4c1e-9d3b-7a1e5c2d4f60";

  @Override
  public Stream<? extends Arguments> provideArguments(final ExtensionContext context) {
    return Arrays.stream(context.getRequiredTestMethod().getAnnotationsByType(ContractCase.class))
        .map(
            contractCase ->
                Arguments.of(
                    contractCase.method().name(), contractCase.path().replaceAll("\\{[^}]+}", ID)));
  }
}
