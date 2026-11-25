package com.example.demo.authorization;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.authorization.AuthorizationExpectations.Shown;
import com.example.demo.authorization.AuthorizationJudgment.BodyExpectation;
import com.example.demo.authorization.AuthorizationJudgment.Exchange;
import com.example.demo.authorization.AuthorizationJudgment.Expectation;
import com.example.demo.authorization.AuthorizationJudgment.Observation;
import com.example.demo.authorization.AuthorizationRequests.Targets;
import com.example.demo.payment.infrastructure.persistence.PaymentSeeds;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.tenancy.TenantId;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.OpenApiOperations.SpecOperation;
import com.example.demo.testkit.UniqueCodes;
import jakarta.servlet.http.Cookie;
import java.io.UnsupportedEncodingException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 認可の表の 1 つの組の場面。持ち主のテナントと別のテナントに操作する人を所属させ、持ち主の注文を作り、要求を送って観測を集める。
 *
 * <p>判定は {@link AuthorizationJudgment} に任せ、ここでは要求を送ることと DB を読み書きすることだけをする。
 */
// Exception を宣言する MockMvc.perform を呼ぶ。
@SuppressWarnings("PMD.SignatureDeclareThrowsException")
final class AuthorizationScene {

  /** 埋め込みサーバのない MockMvc での、Spring Session の Cookie の名前。 */
  private static final String SESSION_COOKIE = "SESSION";

  /** 決済記録の時刻。 */
  private static final Instant RECORDED_AT = Instant.parse("2026-10-01T00:00:00.123456Z");

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  private final MockMvc mockMvc;

  /** 商品と決済記録の行を書き、業務の行を読む jOOQ のコンテキスト。 */
  private final DSLContext dsl;

  /** 持ち主のテナント。 */
  private final UUID home;

  /** 別のテナント。 */
  private final UUID other;

  /** 操作する人の種類ごとの、アプリの利用者の ID。 */
  private final Map<AuthorizationActor, String> users;

  /** 持ち主の利用者の ID。 */
  private final String owner;

  private AuthorizationScene(final MockMvc mockMvc, final DSLContext dsl) {
    this.mockMvc = mockMvc;
    this.dsl = dsl;
    this.home = UUID.randomUUID();
    this.other = UUID.randomUUID();
    this.users =
        Arrays.stream(AuthorizationActor.values())
            .collect(
                Collectors.toUnmodifiableMap(
                    Function.identity(), actor -> UUID.randomUUID().toString()));
    this.owner = users.get(AuthorizationActor.OWNER);
  }

  /** 持ち主のテナントと別のテナントを作り、操作する人の種類ごとに利用者を所属させる。 */
  /* package */ static AuthorizationScene register(final MockMvc mockMvc, final DSLContext dsl) {
    final AuthorizationScene scene = new AuthorizationScene(mockMvc, dsl);
    scene.users.forEach(
        (actor, userId) -> TestTenancy.member(dsl, userId, scene.tenantOf(actor), actor.role()));
    return scene;
  }

  /** MockMvc の応答を、判定が比べる形にする。 */
  /* package */ static Exchange exchange(final MvcResult result)
      throws UnsupportedEncodingException {
    return Exchange.from(
        result.getResponse().getStatus(),
        result.getResponse().getContentAsString(StandardCharsets.UTF_8));
  }

  /**
   * 対象を作り、操作する人として要求を送り、判定に要る期待と観測を集める。
   *
   * <p>見えないことを期待する組では、同じ要求を対象が存在しないときにも送る。id を持つ操作は、対象を作った後に存在しない id で送る。id を持たない操作（一覧）は、対象を作る前に送る。
   *
   * @param operation 操作
   * @param spec 操作の spec
   * @param actor 操作する人
   * @param outcome 判定する結果
   */
  /* package */ Trial trial(
      final AuthorizationOperation operation,
      final SpecOperation spec,
      final AuthorizationActor actor,
      final AuthorizationOutcome outcome)
      throws Exception {
    final RequestPostProcessor login = login(operation, actor);
    final boolean needsAbsent = AuthorizationJudgment.needsAbsentReference(outcome);
    final @Nullable Exchange beforeSeed =
        needsAbsent && !spec.hasId() ? send(operation, Targets.unseeded(owner, home), login) : null;
    final Seeded seeded = seed();
    final Targets targets = new Targets(seeded.order(), seeded.product(), owner, home);
    final @Nullable Exchange absent =
        needsAbsent && spec.hasId() ? send(operation, targets.absent(), login) : beforeSeed;

    final boolean checksRows = AuthorizationJudgment.checksRows(operation, outcome);
    final List<String> rowsBefore = checksRows ? BusinessRows.read(dsl, home, other) : List.of();
    final Exchange actual = send(operation, targets, login);
    final List<String> rowsAfter = checksRows ? BusinessRows.read(dsl, home, other) : List.of();

    return new Trial(
        new Expectation(
            outcome,
            // 存在しない対象は、spec が 404 を書く操作では 404、書かない操作（一覧）では空の 200 になる。
            spec.statuses().contains("404") ? 404 : 200,
            body(AuthorizationExpectations.shown(operation), actor, seeded)),
        new Observation(actual, absent, rowsBefore, rowsAfter));
  }

  /** 許した本文が見せるものを、この場面の ID にする。 */
  private BodyExpectation body(
      final Shown shown, final AuthorizationActor actor, final Seeded seeded) {
    final UUID own = tenantOf(actor);
    final UUID foreign = own.equals(home) ? other : home;
    return switch (shown) {
      case NOTHING -> BodyExpectation.NONE;
      case ACTOR_TENANT ->
          new BodyExpectation(List.of(own.toString()), List.of(foreign.toString()));
      case ACTOR_TENANT_MEMBERS -> new BodyExpectation(usersIn(own), usersIn(foreign));
      case TARGET_ORDER -> new BodyExpectation(List.of(seeded.order().toString()), List.of());
      case TARGET_PAYMENT -> new BodyExpectation(List.of(seeded.payment().toString()), List.of());
      case TARGET_PRODUCT -> new BodyExpectation(List.of(seeded.product().toString()), List.of());
    };
  }

  /** 操作する人のログイン。テナントを選ぶ操作だけは、テナントを選ぶ前の利用者にする。所属は {@link #register} が入れたので DB を書かない。 */
  private RequestPostProcessor login(
      final AuthorizationOperation operation, final AuthorizationActor actor) throws Exception {
    final String userId = users.get(actor);
    return operation == AuthorizationOperation.SELECT_TENANT
        ? beforeTenantSelection(userId)
        : AppUsers.selectedTenant(userId, tenantOf(actor).toString(), actor.role());
  }

  /** 持ち主のテナントに商品を登録し、持ち主が下書きの注文を作り、その注文の決済記録を入れる。 */
  private Seeded seed() throws Exception {
    final TenantId tenant = new TenantId(home);
    final UUID product = TestProducts.onSale(dsl, tenant, UniqueCodes.next("P"), "100.00");
    final String location =
        mockMvc
            .perform(
                AuthorizationRequests.draftOrder(product)
                    .with(AppUsers.selectedTenant(owner, home.toString(), "MEMBER"))
                    .with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getHeader(HttpHeaders.LOCATION);
    final UUID order =
        UUID.fromString(Objects.requireNonNull(location).substring(location.lastIndexOf('/') + 1));
    final UUID payment = UUID.randomUUID();
    AsModule.run(
        dsl,
        "payment",
        tenant,
        tx ->
            PaymentSeeds.insert(tx, payment, order, new BigDecimal("200.00"), false, RECORDED_AT));
    return new Seeded(order, product, payment);
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

  private Exchange send(
      final AuthorizationOperation operation,
      final Targets targets,
      final RequestPostProcessor login)
      throws Exception {
    return exchange(
        mockMvc
            .perform(AuthorizationRequests.build(operation, targets).with(login).with(csrf()))
            .andReturn());
  }

  private UUID tenantOf(final AuthorizationActor actor) {
    return actor.otherTenant() ? other : home;
  }

  private List<String> usersIn(final UUID tenant) {
    return users.entrySet().stream()
        .filter(user -> tenantOf(user.getKey()).equals(tenant))
        .map(Map.Entry::getValue)
        .sorted()
        .toList();
  }

  /**
   * 1 つの組の判定に渡す期待と観測。
   *
   * @param expectation 期待
   * @param observation 観測
   */
  /* package */ record Trial(Expectation expectation, Observation observation) {}

  /**
   * 持ち主が作った対象。
   *
   * @param order 持ち主の下書きの注文
   * @param product 持ち主のテナントの販売中の商品
   * @param payment 注文の決済記録
   */
  private record Seeded(UUID order, UUID product, UUID payment) {}
}
