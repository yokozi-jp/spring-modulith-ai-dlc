package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.AuthorizationExpectations.AuthorizationExemption;
import com.example.demo.AuthorizationJudgment.Exchange;
import com.example.demo.AuthorizationJudgment.Observation;
import com.example.demo.AuthorizationRequests.Targets;
import com.example.demo.payment.infrastructure.persistence.PaymentSeeds;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * commit した OpenAPI の operation と操作する人の組ごとに、実際の Security filter chain と DB
 * を通した応答が認可の期待どおりかを確かめる（ADR-129）。
 *
 * <p>期待は {@link AuthorizationExpectations}、判定は {@link AuthorizationJudgment}
 * にある。組ごとに、持ち主のテナントと別のテナントと 4 種類の操作する人を作り、持ち主が注文を作ってから要求を送る。
 */
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。1 つの組の準備、要求、観測を手順ごとのメソッドに分けるため、メソッドが多い。
@SuppressWarnings({"PMD.SignatureDeclareThrowsException", "PMD.TooManyMethods"})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class AuthorizationMatrixTest {

  /** spec の operation（「メソッド パス」）ごとの、パスか query に id を持つか。 */
  private static final Map<String, Boolean> SPEC_OPERATIONS = OpenApiOperations.withIdParameter();

  /** 埋め込みサーバのない MockMvc での、Spring Session の Cookie の名前。 */
  private static final String SESSION_COOKIE = "SESSION";

  /** 決済記録の時刻。 */
  private static final Instant RECORDED_AT = Instant.parse("2026-10-01T00:00:00.123456Z");

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
  @DisplayName("例外の一覧の組は、spec にある operation を指し、理由と Issue の番号を持つ")
  void exemptionsNameSpecOperationsWithReasonAndIssue() {
    assertThat(AuthorizationExpectations.EXEMPTIONS)
        .as("例外の一覧")
        .allSatisfy(exemption -> {
          assertThat(SPEC_OPERATIONS)
              .as("%s の operation", exemption)
              .containsKey(exemption.operation().key());
          assertThat(exemption.reason()).as("%s の理由", exemption).isNotBlank();
          assertThat(exemption.issue()).as("%s の Issue の番号", exemption).isPositive();
        });
  }

  @ParameterizedTest(name = "{0} {1}")
  @MethodSource("pairs")
  @DisplayName("操作と操作する人の組は期待の表どおりに許すか拒否し、違ってよいのは例外の一覧の組だけである")
  void pairMeetsExpectation(final AuthorizationOperation operation, final AuthorizationActor actor)
      throws Exception {
    final Scene scene = registerScene();
    final AuthorizationOutcome expected = AuthorizationExpectations.expected(operation, actor);

    final List<String> violations =
        AuthorizationJudgment.violations(
            expected, observe(operation, expected, login(operation, actor, scene), scene));

    final Optional<AuthorizationExemption> exemption =
        AuthorizationExpectations.exemptionOf(operation, actor);
    if (exemption.isPresent()) {
      assertThat(violations).as("例外の一覧の組が期待どおりになった。例外の一覧から消す: %s", exemption.get()).isNotEmpty();
    } else {
      assertThat(violations).as("%s %s の期待 %s", operation, actor, expected).isEmpty();
    }
  }

  /** 持ち主のテナントと別のテナントを作り、操作する人の種類ごとに利用者を所属させる。 */
  private Scene registerScene() {
    final Scene scene =
        new Scene(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Arrays.stream(AuthorizationActor.values())
                .collect(
                    Collectors.toUnmodifiableMap(
                        Function.identity(), actor -> UUID.randomUUID().toString())));
    scene
        .users()
        .forEach((actor, userId) ->
            TestTenancy.member(dsl, userId, scene.tenantOf(actor), actor.role()));
    return scene;
  }

  /** 操作する人のログイン。テナントを選ぶ操作だけは、テナントを選ぶ前の利用者にする。 */
  private RequestPostProcessor login(
      final AuthorizationOperation operation, final AuthorizationActor actor, final Scene scene)
      throws Exception {
    final String userId = scene.users().get(actor);
    return operation == AuthorizationOperation.SELECT_TENANT
        ? beforeTenantSelection(userId)
        : AppUsers.inTenant(userId, scene.tenantOf(actor).toString(), actor.role());
  }

  /**
   * 対象を作り、要求を送り、判定に要る応答と業務の行を集める。
   *
   * <p>見えないことを期待する組では、同じ要求を対象が存在しないときにも送る。id を持つ操作は、対象を作った後に存在しない id で送る。id を持たない操作（一覧）は、対象を作る前に送る。
   */
  private Observation observe(
      final AuthorizationOperation operation,
      final AuthorizationOutcome expected,
      final RequestPostProcessor login,
      final Scene scene)
      throws Exception {
    final boolean hidden = expected == AuthorizationOutcome.HIDDEN;
    final boolean hasId = Boolean.TRUE.equals(SPEC_OPERATIONS.get(operation.key()));
    final MvcResult beforeSeed =
        hidden && !hasId
            ? perform(operation, Targets.unseeded(scene.owner(), scene.home()), login)
            : null;
    final Targets targets = seed(scene);
    final MvcResult absent =
        hidden && hasId ? perform(operation, targets.absent(), login) : beforeSeed;

    final boolean measureRows = operation.writes() && expected != AuthorizationOutcome.ALLOWED;
    final List<String> rowsBefore = businessRows(measureRows, scene);
    final MvcResult actual = perform(operation, targets, login);
    final List<String> rowsAfter = businessRows(measureRows, scene);

    if (hidden && hasId) {
      assertThat(target(actual))
          .as("%s の要求は、パスか query の id で対象を指す（AuthorizationRequests）", operation)
          .isNotEqualTo(target(Objects.requireNonNull(absent)));
    }
    return new Observation(
        Exchange.from(actual),
        absent == null ? null : Exchange.from(absent),
        rowsBefore,
        rowsAfter);
  }

  /** 持ち主のテナントに商品を登録し、持ち主が下書きの注文を作り、その注文の決済記録を入れる。 */
  private Targets seed(final Scene scene) throws Exception {
    final TenantId home = new TenantId(scene.home());
    final UUID product = TestProducts.onSale(dsl, home, UniqueCodes.next("P"), "100.00");
    final String location =
        mockMvc
            .perform(
                AuthorizationRequests.build(
                        AuthorizationOperation.DRAFT_ORDER,
                        new Targets(UUID.randomUUID(), product, scene.owner(), scene.home()))
                    .with(AppUsers.inTenant(scene.owner(), scene.home().toString(), "MEMBER"))
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);
    final UUID order =
        UUID.fromString(Objects.requireNonNull(location).substring(location.lastIndexOf('/') + 1));
    AsModule.run(
        dsl,
        "payment",
        home,
        tx ->
            PaymentSeeds.insert(
                tx, UUID.randomUUID(), order, new BigDecimal("200.00"), false, RECORDED_AT));
    return new Targets(order, product, scene.owner(), scene.home());
  }

  /**
   * ログインの後と同じく Spring Session のセッションを持ち、テナントを選ぶ前の利用者にする。
   *
   * <p>テナントを選ぶとセッションの ID を変えるため、セッションが要る。セッションは、OIDC のログインを始める要求で作る。
   */
  private RequestPostProcessor beforeTenantSelection(final String userId) throws Exception {
    final Cookie session =
        Objects.requireNonNull(
            mockMvc
                .perform(get("/oauth2/authorization/web"))
                .andReturn()
                .getResponse()
                .getCookie(SESSION_COOKIE),
            "OIDC のログインを始める要求がセッションを作らない");
    final RequestPostProcessor login = AppUsers.beforeTenantSelection(userId);
    return request -> {
      request.setCookies(session);
      return login.postProcessRequest(request);
    };
  }

  private MvcResult perform(
      final AuthorizationOperation operation,
      final Targets targets,
      final RequestPostProcessor login)
      throws Exception {
    return mockMvc
        .perform(AuthorizationRequests.build(operation, targets).with(login).with(csrf()))
        .andReturn();
  }

  /** 書き込みを拒否する組では、両方のテナントの業務の行を読む。ほかの組では読まず、空を返す。 */
  private List<String> businessRows(final boolean measure, final Scene scene) {
    return measure ? BusinessRows.read(dsl, scene.home(), scene.other()) : List.of();
  }

  /** 要求が指す対象（パスと query の引数）。 */
  private static String target(final MvcResult result) {
    return result.getRequest().getRequestURI()
        + result.getRequest().getParameterMap().entrySet().stream()
            .map(parameter -> parameter.getKey() + "=" + String.join(",", parameter.getValue()))
            .sorted()
            .toList();
  }

  /**
   * 1 つの組のテナントと利用者。
   *
   * @param home 持ち主のテナント
   * @param other 別のテナント
   * @param users 操作する人の種類ごとの、アプリの利用者の ID
   */
  private record Scene(UUID home, UUID other, Map<AuthorizationActor, String> users) {

    /** 持ち主の利用者の ID。 */
    private String owner() {
      return users.get(AuthorizationActor.OWNER);
    }

    /** 操作する人が所属するテナント。 */
    private UUID tenantOf(final AuthorizationActor actor) {
      return actor.otherTenant() ? other : home;
    }
  }
}
