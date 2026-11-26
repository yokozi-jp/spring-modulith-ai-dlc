package com.example.demo.ordering.presentation.web;

import static com.example.demo.jooq.ordering.Tables.T_IDEMPOTENCY_KEY;
import static com.example.demo.testkit.AppUsers.oidcLogin;
import static com.example.demo.testkit.IdempotencyKeys.expectIdempotencyProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.ordering.OrderConfirmed;
import com.example.demo.ordering.application.ConfirmOrderCommand;
import com.example.demo.ordering.application.ConfirmOrderCommandHandler;
import com.example.demo.product.TestProducts;
import com.example.demo.shared.concurrency.ExpectedLockNo;
import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.shared.persistence.TestCommonColumns;
import com.example.demo.shared.tenancy.TenantContext;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.AsModule;
import com.example.demo.testkit.AsTenant;
import com.example.demo.testkit.CapturedLogRecords;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.IdempotencyKeys;
import com.example.demo.testkit.SharedTestConfiguration;
import com.example.demo.testkit.UniqueCodes;
import io.micrometer.observation.ObservationRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * 注文の更新の API が、Idempotency-Key で再送に 1 回目と同じ応答を返し、キーの誤りを処理の前に 400 にすることを HTTP の形で確かめる（ADR-126）。
 *
 * <p>OrderApiTest と同じ構成にして、Spring のコンテキストを共有する。
 */
// MockMvc の期待値は andExpect で書く。補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
// 同じキーの 2 つの要求を並行に動かすため、テストのスレッドとは別のスレッドを使う。
// HTTP、DB、ログ、observation を 1 つの要求の流れで確かめるため、結合するクラスの数の上限を外す。
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.DoNotUseThreads",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@RecordApplicationEvents
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class OrderIdempotencyApiTest {

  /** 同じキーの要求の終わりを待つ上限（DB_LOCK_TIMEOUT_MS）より十分に長い、テストの待ちの上限。 */
  private static final Duration WAIT = Duration.ofSeconds(10);

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 商品の行を登録し、DB のロック待ちを読む jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** 1 つ目の要求として、トランザクションを開いたまま止める確定の CommandHandler。 */
  @Autowired private ConfirmOrderCommandHandler confirmOrder;

  /** 共通カラムの trace ID を作る observation registry。 */
  @Autowired private ObservationRegistry observationRegistry;

  /** API が出したログ。 */
  @Autowired private CapturedLogRecords capturedLogRecords;

  /** ログの appender の送り先にする、このコンテキストの OpenTelemetry。 */
  @Autowired private OpenTelemetry openTelemetry;

  /** 1 つ目の要求のトランザクションの、DB のバックエンドのプロセス ID。 */
  private final AtomicInteger holderPid = new AtomicInteger();

  /** 同じキーの 2 つの要求を並行に送るスレッド。 */
  private final ExecutorService executor = Executors.newFixedThreadPool(2);

  /** 1 つ目の要求のトランザクションを開いたまま止める latch。 */
  private final CountDownLatch release = new CountDownLatch(1);

  /** 販売中で単価 120 円の商品。 */
  private UUID pen;

  @BeforeEach
  void registerProduct() {
    // 別のコンテキストが appender の送り先を差し替えていても、このコンテキストの記録を読む。
    OpenTelemetryAppender.install(openTelemetry);
    pen = TestProducts.onSale(dsl, UniqueCodes.next("P"), "120.00");
  }

  @AfterEach
  void stopThreads() {
    release.countDown();
    executor.shutdownNow();
  }

  @Test
  @DisplayName("確定した後に同じキーと同じ本文で再送すると、409 でなく 204 を返し、OrderConfirmed は 1 回だけ発行する")
  void confirmReplayReturnsNoContent(final ApplicationEvents events) throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();

    confirm(orderId, key).andExpect(status().isNoContent());
    confirm(orderId, key).andExpect(status().isNoContent());

    details(orderId)
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.lockNo").value(2));
    assertThat(events.stream(OrderConfirmed.class).filter(e -> e.orderId().equals(orderId)))
        .as("orderId=%s の OrderConfirmed", orderId)
        .hasSize(1);
  }

  @Test
  @DisplayName("注文を作った後に同じキーと同じ本文で再送すると、1 回目と同じ 201 と Location を返し、注文は 1 件だけできる")
  void draftReplayReturnsSameLocation() throws Exception {
    final String customerOrderCode = UniqueCodes.next("C");
    final IdempotencyKey key = IdempotencyKeys.next();

    final String first = location(draft(customerOrderCode, pen, IdempotencyKeys.header(key)));
    final String replayed = location(draft(customerOrderCode, pen, IdempotencyKeys.header(key)));

    assertThat(replayed).as("key=%s の再送の Location", key.value()).isEqualTo(first);
    orderCount().andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("同じキーで本文の違う要求は、ペイロードの不一致の 400 を返し、注文を作らない")
  void differentPayloadReturnsBadRequest() throws Exception {
    final IdempotencyKey key = IdempotencyKeys.next();
    location(draft(UniqueCodes.next("C"), pen, IdempotencyKeys.header(key)));

    expectIdempotencyProblem(
        draft(UniqueCodes.next("C"), pen, IdempotencyKeys.header(key)),
        400,
        "idempotency-key-payload-mismatch");
    orderCount().andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("業務の失敗の 422 の後に、同じキーで直した本文を送ると、もう一度処理して 201 を返す")
  void businessFailureDoesNotKeepKey() throws Exception {
    final IdempotencyKey key = IdempotencyKeys.next();
    final String customerOrderCode = UniqueCodes.next("C");
    draft(customerOrderCode, UUID.randomUUID(), IdempotencyKeys.header(key))
        .andExpect(status().isUnprocessableContent());

    location(draft(customerOrderCode, pen, IdempotencyKeys.header(key)));
    orderCount().andExpect(jsonPath("$.items.length()").value(1));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"POST, ''", "PUT, /lines", "POST, /confirm", "POST, /cancel"})
  @DisplayName("Idempotency-Key のない更新は、本文の検証より先にキーなしの 400 を返し、注文を変えない")
  void missingKeyReturnsBadRequest(final String method, final String suffix) throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final String before = detailsBody(orderId);
    final String path = suffix.isEmpty() ? "/api/v1/orders" : "/api/v1/orders/" + orderId + suffix;

    // 本文は検証で 400 になる空の JSON にし、キーの検査が先に動くことを確かめる。
    expectIdempotencyProblem(
        mockMvc.perform(write(HttpMethod.valueOf(method), path, "{}")),
        400,
        "idempotency-key-missing");

    assertThat(detailsBody(orderId)).as("orderId=%s の 400 の後の詳細", orderId).isEqualTo(before);
    orderCount().andExpect(jsonPath("$.items.length()").value(1));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "8e03978e-40d5-43e8-bc93-6894a57f9324",
        "\"not-a-uuid\"",
        "\"1-1-1-1-1\"",
        "\"8e03978e-40d5-43e8-bc93-6894a57f9324\", \"8e03978e-40d5-43e8-bc93-6894a57f9325\""
      })
  @DisplayName("引用符で囲んだ UUID でない Idempotency-Key は、形式の誤りの 400 を返し、注文を変えない")
  void malformedKeyReturnsBadRequest(final String value) throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));

    expectIdempotencyProblem(
        mockMvc.perform(
            write(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "{\"lockNo\":1}")
                .header(IdempotencyKey.HEADER, value)),
        400,
        "idempotency-key-invalid");

    details(orderId).andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  @DisplayName("同じキーの 2 つ目の要求は 1 つ目の終わりを待ち、1 つ目と同じ 204 を返して、確定は 1 回だけ動く")
  void concurrentRequestWaitsAndReplays() throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();
    final Future<?> first = holdConfirm(orderId, key);

    final Future<MvcResult> second = executor.submit(() -> confirm(orderId, key).andReturn());
    awaitSecondWaitsForFirst(key);
    release.countDown();
    first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS).getResponse().getStatus())
        .as("key=%s の 2 つ目の要求", key.value())
        .isEqualTo(204);
    details(orderId).andExpect(jsonPath("$.lockNo").value(2));
  }

  @Test
  @DisplayName("期限切れの行を同じキーの 2 つの要求が取り直すと、2 つ目は 1 つ目を待って同じ 204 を返し、確定は 1 回だけ動く")
  void concurrentTakeoverOfExpiredKeyRunsOnce() throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();
    insertExpiredKey(key);
    final Future<?> first = holdConfirm(orderId, key);

    final Future<MvcResult> second = executor.submit(() -> confirm(orderId, key).andReturn());
    awaitSecondWaitsForFirst(key);
    release.countDown();
    first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    // 2 つ目も業務を動かしていれば、版の 1 は古いので 204 でなく 409 になる。
    assertThat(second.get(WAIT.toSeconds(), TimeUnit.SECONDS).getResponse().getStatus())
        .as("key=%s の 2 つ目の要求", key.value())
        .isEqualTo(204);
    details(orderId).andExpect(jsonPath("$.lockNo").value(2));
  }

  @Test
  @DisplayName("確定に使ったキーで、同じ本文の取消を送ると、操作の名前が違うのでペイロードの不一致の 400 を返し、注文を変えない")
  void sameKeyForAnotherOperationReturnsBadRequest() throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();
    confirm(orderId, key).andExpect(status().isNoContent());

    // 確定と取消の Command は同じ JSON になるので、操作の名前だけがペイロードを分ける。
    expectIdempotencyProblem(
        mockMvc.perform(
            write(HttpMethod.POST, "/api/v1/orders/" + orderId + "/cancel", "{\"lockNo\":1}")
                .with(IdempotencyKeys.header(key))),
        400,
        "idempotency-key-payload-mismatch");
    details(orderId)
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.lockNo").value(2));
  }

  @Test
  @DisplayName("ある注文の確定に使ったキーで、パスの注文 ID だけが違う確定を送ると、ペイロードの不一致の 400 を返し、その注文を確定しない")
  void sameKeyForAnotherOrderReturnsBadRequest() throws Exception {
    final String confirmed = draftAndGetId(UniqueCodes.next("C"));
    final String other = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();
    confirm(confirmed, key).andExpect(status().isNoContent());

    expectIdempotencyProblem(confirm(other, key), 400, "idempotency-key-payload-mismatch");
    details(other).andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  @DisplayName("Idempotency-Key のヘッダーが 2 行あれば、値が同じでも形式の誤りの 400 を返し、注文を変えない")
  void repeatedKeyHeaderReturnsBadRequest() throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();

    expectIdempotencyProblem(
        mockMvc.perform(
            write(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "{\"lockNo\":1}")
                .with(IdempotencyKeys.header(key))
                .with(IdempotencyKeys.header(key))),
        400,
        "idempotency-key-invalid");
    details(orderId).andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  @DisplayName("同じキーの 1 つ目の要求が lock_timeout より長く続くと、2 つ目は Retry-After 付きの処理中の 409 を返す")
  void concurrentRequestTimesOutAsInProgress() throws Exception {
    final String orderId = draftAndGetId(UniqueCodes.next("C"));
    final IdempotencyKey key = IdempotencyKeys.next();
    final Future<?> first = holdConfirm(orderId, key);

    expectIdempotencyProblem(confirm(orderId, key), 409, "idempotency-key-in-progress")
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "1"));
    final LogRecordData logged = capturedLogRecords.withBody("API conflict").getLast();
    assertThat(logged.getAttributes().get(AttributeKey.stringKey("problem.type")))
        .as("処理中の 409 のログの problem.type")
        .isEqualTo("/problems/idempotency-key-in-progress");
    assertThat(logged.getAttributes().get(AttributeKey.stringKey("conflict.kind")))
        .as("処理中の 409 のログの conflict.kind")
        .isEqualTo("idempotency_in_progress");
    release.countDown();
    first.get(WAIT.toSeconds(), TimeUnit.SECONDS);

    confirm(orderId, key).andExpect(status().isNoContent());
    details(orderId).andExpect(jsonPath("$.lockNo").value(2));
  }

  /**
   * 1 つ目の要求として、テストの利用者とテナントで確定を処理し、commit の前で {@link #release} を待つ。
   *
   * <p>本番の要求と同じく、ordering のロールのトランザクションで CommandHandler を呼ぶ。処理を終えるまで戻らない。
   */
  private Future<?> holdConfirm(final String orderId, final IdempotencyKey key) throws Exception {
    final CountDownLatch handled = new CountDownLatch(1);
    final Future<?> first =
        executor.submit(() ->
            TenantContext.call(
                AsTenant.TENANT,
                () ->
                    AppUsers.signedInAndObserved(
                        observationRegistry,
                        () ->
                            AsModule.call(
                                dsl,
                                "ordering",
                                tx -> {
                                  holderPid.set(
                                      tx.fetchSingle("select pg_backend_pid()")
                                          .get(0, Integer.class));
                                  confirmOrder.handle(
                                      new ConfirmOrderCommand(orderId, new ExpectedLockNo(1), key));
                                  handled.countDown();
                                  return awaitRelease();
                                }))));
    assertThat(handled.await(WAIT.toSeconds(), TimeUnit.SECONDS))
        .as("key=%s の 1 つ目の要求の処理", key.value())
        .isTrue();
    return first;
  }

  private boolean awaitRelease() {
    try {
      return release.await(WAIT.toSeconds(), TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /** 2 つ目の要求が、1 つ目の要求のトランザクションの持つロックを待つまで待つ。 */
  private void awaitSecondWaitsForFirst(final IdempotencyKey key) {
    await("key=" + key.value() + " の 2 つ目の要求が 1 つ目の要求のロックを待つ")
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(10))
        .untilAsserted(() ->
            assertThat(
                    dsl.fetchSingle(
                            "select count(*)::int from pg_stat_activity"
                                + " where wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))",
                            holderPid.get())
                        .get(0, Integer.class))
                .as("1 つ目の要求（pid=%s）のロックを待つ接続", holderPid.get())
                .isPositive());
  }

  /** テストの利用者のテナントに、期限を過ぎたキーの行を入れる。 */
  private void insertExpiredKey(final IdempotencyKey key) {
    final Instant expired = Instant.parse("2026-01-01T00:00:00.000001Z");
    AsModule.run(
        dsl,
        "ordering",
        tx ->
            TestCommonColumns.runAs(() ->
                tx.insertInto(T_IDEMPOTENCY_KEY)
                    .set(T_IDEMPOTENCY_KEY.USER_PUBLIC_ID, UUID.fromString(AppUsers.USER_ID))
                    .set(T_IDEMPOTENCY_KEY.IDEMPOTENCY_KEY, key.value())
                    .set(T_IDEMPOTENCY_KEY.PAYLOAD_HASH, new byte[] {1})
                    .set(T_IDEMPOTENCY_KEY.EXPIRES_AT, expired)
                    .set(TestCommonColumns.at(expired).forInsert(T_IDEMPOTENCY_KEY))
                    .execute()));
  }

  private ResultActions confirm(final String orderId, final IdempotencyKey key) throws Exception {
    return mockMvc.perform(
        write(HttpMethod.POST, "/api/v1/orders/" + orderId + "/confirm", "{\"lockNo\":1}")
            .with(IdempotencyKeys.header(key)));
  }

  private ResultActions draft(
      final String customerOrderCode, final UUID productId, final RequestPostProcessor key)
      throws Exception {
    return mockMvc.perform(
        post("/api/v1/orders")
            .with(oidcLogin())
            .with(csrf())
            .with(key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                String.format(
                    Locale.ROOT,
                    "{\"customerOrderCode\":\"%s\",\"lines\":[{\"productId\":\"%s\",\"quantity\":1}]}",
                    customerOrderCode,
                    productId)));
  }

  private String draftAndGetId(final String customerOrderCode) throws Exception {
    final String path = location(draft(customerOrderCode, pen, IdempotencyKeys.fresh()));
    return path.substring(path.lastIndexOf('/') + 1);
  }

  private static String location(final ResultActions actions) throws Exception {
    return Objects.requireNonNull(
        actions.andExpect(status().isCreated()).andReturn().getResponse().getHeader("Location"),
        "POST /api/v1/orders の Location");
  }

  /** キーのない、ログインと CSRF token の付いた更新の要求を作る。 */
  private static MockHttpServletRequestBuilder write(
      final HttpMethod method, final String path, final String body) {
    return MockMvcRequestBuilders.request(method, path)
        .with(oidcLogin())
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)
        .content(body);
  }

  private ResultActions details(final String orderId) throws Exception {
    return mockMvc.perform(get("/api/v1/orders/" + orderId).with(oidcLogin()));
  }

  private String detailsBody(final String orderId) throws Exception {
    return details(orderId)
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private ResultActions orderCount() throws Exception {
    return mockMvc.perform(get("/api/v1/orders").with(oidcLogin()));
  }
}
