package com.example.demo.authorization;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.demo.authorization.AuthorizationExpectations.AuthorizationExemption;
import com.example.demo.authorization.AuthorizationRequests.Targets;
import com.example.demo.authorization.AuthorizationScene.Trial;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.OpenApiOperations;
import com.example.demo.testkit.OpenApiOperations.SpecOperation;
import com.example.demo.testkit.SharedTestConfiguration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;

/**
 * commit した OpenAPI の operation と操作する人の組ごとに、実際の Security filter chain と DB
 * を通した応答が認可の期待どおりかを確かめる（ADR-129）。
 *
 * <p>期待は {@link AuthorizationExpectations}、判定は {@link AuthorizationJudgment}、組ごとのテナント、利用者、注文と要求の送信は
 * {@link AuthorizationScene} にある。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class AuthorizationMatrixTest {

  /** spec の operation の、「メソッド パス」のキーごとの値。 */
  private static final Map<String, SpecOperation> SPEC_OPERATIONS =
      OpenApiOperations.operations().stream()
          .collect(Collectors.toUnmodifiableMap(SpecOperation::key, Function.identity()));

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 利用者、商品、決済記録の行を書き、業務の行を読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  private static Stream<Arguments> pairs() {
    return Arrays.stream(AuthorizationOperation.values())
        .flatMap(operation ->
            Arrays.stream(AuthorizationActor.values())
                .map(actor -> Arguments.of(operation, actor)));
  }

  @Test
  @DisplayName("操作の enum の定数は、commit した OpenAPI の operation と過不足なく一致する")
  void operationsMatchSpec() {
    final Set<String> declared =
        Arrays.stream(AuthorizationOperation.values())
            .map(AuthorizationOperation::key)
            .collect(Collectors.toCollection(TreeSet::new));

    final Set<String> missing = new TreeSet<>(SPEC_OPERATIONS.keySet());
    missing.removeAll(declared);
    assertThat(missing)
        .as("AuthorizationOperation にない operation。定数と期待を足す（%s）", OpenApiOperations.OPENAPI)
        .isEmpty();
    final Set<String> unknown = new TreeSet<>(declared);
    unknown.removeAll(SPEC_OPERATIONS.keySet());
    assertThat(unknown).as("spec にない AuthorizationOperation の定数。メソッドとパスを直すか、定数を消す").isEmpty();
  }

  @Test
  @DisplayName("例外の一覧の組は、spec にある operation を指し、期待と違う今の結果、理由、Issue の番号を持つ")
  void exemptionsNameSpecOperationsWithCurrentReasonAndIssue() {
    assertThat(AuthorizationExpectations.EXEMPTIONS)
        .as("例外の一覧")
        .allSatisfy(exemption -> {
          assertThat(SPEC_OPERATIONS)
              .as("%s の operation", exemption)
              .containsKey(exemption.operation().key());
          assertThat(exemption.current())
              .as("%s の今の結果。期待と同じなら例外は要らない", exemption)
              .isNotEqualTo(
                  AuthorizationExpectations.expected(exemption.operation(), exemption.actor()));
          assertThat(exemption.reason()).as("%s の理由", exemption).isNotBlank();
          assertThat(exemption.issue()).as("%s の Issue の番号", exemption).isPositive();
        });
  }

  @Test
  @DisplayName("id を持つ操作の要求は、存在しない id に替えるとパスか query が変わり、id で対象を指す")
  void requestsWithIdPointAtTargets() {
    final Targets targets =
        new Targets(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), UUID.randomUUID());

    assertThat(AuthorizationOperation.values())
        .filteredOn(operation -> SPEC_OPERATIONS.get(operation.key()).hasId())
        .as("id を持つ操作（AuthorizationRequests）")
        .isNotEmpty()
        .allSatisfy(operation ->
            assertThat(target(operation, targets))
                .as("%s の要求が指す対象", operation)
                .isNotEqualTo(target(operation, targets.absent())));
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("pairs")
  @DisplayName("操作と操作する人の組は期待の表どおりに許すか拒否し、違ってよいのは例外の一覧の組の今の結果だけである")
  void pairMeetsExpectation(final AuthorizationOperation operation, final AuthorizationActor actor)
      throws Exception {
    final AuthorizationOutcome judged = AuthorizationExpectations.judged(operation, actor);

    final Trial trial =
        AuthorizationScene.register(mockMvc, dsl)
            .trial(operation, SPEC_OPERATIONS.get(operation.key()), actor, judged);

    final Optional<AuthorizationExemption> exemption =
        AuthorizationExpectations.exemptionOf(operation, actor);
    assertThat(AuthorizationJudgment.violations(trial.expectation(), trial.observation()))
        .as(
            exemption
                .map(found ->
                    "例外の一覧の組が今の結果 " + found.current() + " と違う。理由の Issue で直ったなら、例外の一覧から消す: " + found)
                .orElseGet(() -> operation + " " + actor + " の期待 " + judged))
        .isEmpty();
  }

  /** 要求が指す対象（パスと query の引数）。 */
  private static String target(final AuthorizationOperation operation, final Targets targets) {
    final MockHttpServletRequest request =
        AuthorizationRequests.build(operation, targets).buildRequest(new MockServletContext());
    return request.getRequestURI()
        + request.getParameterMap().entrySet().stream()
            .map(parameter -> parameter.getKey() + "=" + String.join(",", parameter.getValue()))
            .sorted()
            .toList();
  }
}
