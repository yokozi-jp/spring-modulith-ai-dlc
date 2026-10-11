package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.architecture.TestCodeOnly;
import com.example.demo.testkit.ContractCase;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.swagger.v3.core.util.Yaml31;
import io.swagger.v3.oas.models.OpenAPI;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * コミット済みの OpenAPI 契約の operation と応答の status の組ごとに、MockMvc の契約テストがあることを検査する（ADR-076）。
 *
 * <p>契約テストは {@link ContractCase} で確かめる組を宣言する。この検査は Spring も DB も起動しない。
 */
class ApiContractCoverageTest {

  /** コミット済みの OpenAPI 契約。テストは backend を作業ディレクトリにして走る。 */
  private static final Path OPENAPI = Path.of("..", "openapi", "openapi.yaml");

  /**
   * OpenAPI に載らない {@code /error} が返しうる status。
   *
   * <p>{@code ApiErrorController} は転送元の status を保つため、title を持つ status をすべて契約テストで確かめる。
   */
  private static final Set<Integer> ERROR_ENDPOINT_STATUSES =
      Set.of(400, 401, 403, 404, 405, 406, 409, 413, 415, 422, 429, 500, 503);

  /**
   * operation ごとの契約テストを求めない組と理由。キーは「HTTP メソッド status」で、メソッドの {@code *} はすべてのメソッドを表す。
   *
   * <p>どちらも {@code OpenApiConfig} の customizer が全 operation に付ける共通の応答である。
   */
  private static final Map<String, String> EXEMPTIONS =
      Map.of(
          "* 500",
          "operation に依らない ApiExceptionHandler の catch-all が返す。"
              + "ApiErrorContractTest の未処理例外と JDK の例外の case で確かめる。",
          "GET 403",
          "GET は CSRF の検査の対象外で、/api/** に権限の規則もないため、403 を起こす経路がない。"
              + "権限の規則を加えたら、この除外を消して case を足す。");

  @Test
  @DisplayName("契約の operation と status の組と /error の status は、すべて MockMvc の契約テストで確かめる")
  void everyDeclaredResponseHasContractCase() throws IOException {
    final Set<String> declared = declaredResponses();
    final Set<String> covered = coveredResponses();

    final Set<String> missing = new TreeSet<>(declared);
    missing.removeAll(covered);
    missing.removeIf(ApiContractCoverageTest::isExempt);
    assertThat(missing).as("@ContractCase のない組。契約テストを足し、@ContractCase を付ける（%s）", OPENAPI).isEmpty();

    final Set<String> unknown = new TreeSet<>(covered);
    unknown.removeAll(declared);
    assertThat(unknown).as("契約にない組を宣言した @ContractCase。path の形と status を直す").isEmpty();

    final Set<String> unusedExemptions = new TreeSet<>(EXEMPTIONS.keySet());
    unusedExemptions.removeIf(
        exemption ->
            declared.stream()
                .filter(response -> !covered.contains(response))
                .anyMatch(response -> matches(exemption, response)));
    assertThat(unusedExemptions).as("どの組にも当たらなくなった除外。EXEMPTIONS から消す").isEmpty();
  }

  /** 契約の operation と応答の status の組と、{@code /error} の status を「メソッド path status」で返す。 */
  private static Set<String> declaredResponses() throws IOException {
    final OpenAPI openApi = Yaml31.mapper().readValue(OPENAPI.toFile(), OpenAPI.class);
    final Set<String> responses = new TreeSet<>();
    openApi
        .getPaths()
        .forEach(
            (path, item) ->
                item.readOperationsMap()
                    .forEach(
                        (method, operation) ->
                            operation
                                .getResponses()
                                .keySet()
                                .forEach(
                                    status -> responses.add(key(method.name(), path, status)))));
    ERROR_ENDPOINT_STATUSES.forEach(
        status -> responses.add(key("GET", "/error", String.valueOf(status))));
    return responses;
  }

  /** 手書きのテストコードに付いた {@link ContractCase} の組を返す。 */
  // ponytail: 宣言は自己申告で、テストが実際にその status を期待するかは見ない。
  // 宣言と andExpect のずれが問題になったら、MockMvc の実行結果を記録して突き合わせる方式に替える（ADR-076）。
  private static Set<String> coveredResponses() {
    return new ClassFileImporter()
        .withImportOption(new TestCodeOnly()).importPackagesOf(DemoApplication.class).stream()
            .flatMap(javaClass -> javaClass.getMethods().stream())
            .map(JavaMethod::reflect)
            .flatMap(method -> Arrays.stream(method.getAnnotationsByType(ContractCase.class)))
            .flatMap(
                contractCase ->
                    IntStream.of(contractCase.status())
                        .mapToObj(
                            status ->
                                key(
                                    contractCase.method().name(),
                                    contractCase.path(),
                                    String.valueOf(status))))
            .collect(Collectors.toCollection(TreeSet::new));
  }

  private static boolean isExempt(final String response) {
    return EXEMPTIONS.keySet().stream().anyMatch(exemption -> matches(exemption, response));
  }

  /**
   * 除外のキー「メソッド status」が、組「メソッド path status」に当たるかを返す。
   *
   * <p>除外の理由は OpenAPI の operation にだけ当てはまるため、{@code /error} の組には当てない。
   */
  private static boolean matches(final String exemption, final String response) {
    if (response.startsWith(key("GET", "/error", ""))) {
      return false;
    }
    final int space = exemption.indexOf(' ');
    final String method = exemption.substring(0, space);
    final String status = exemption.substring(space);
    return ("*".equals(method) || response.startsWith(method + " ")) && response.endsWith(status);
  }

  private static String key(final String method, final String path, final String status) {
    return method + " " + path + " " + status;
  }
}
