package com.example.demo.tenancy.presentation.web;

import static com.example.demo.testkit.AppUsers.inTenant;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.shared.idempotency.IdempotencyKey;
import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.IdempotencyKeys;
import com.example.demo.testkit.SharedTestConfiguration;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.result.JsonPathResultMatchers;

/**
 * 選んだテナントの所属の更新の API が、Idempotency-Key で再送に 1 回目と同じ応答を返し、キーの誤りを処理の前に 400 にすることを HTTP
 * の形で確かめる（ADR-126）。
 *
 * <p>{@code tenancy} のキーの表は RLS の外にあるので、別のテナントの同じキーに当たらないことも確かめる。CurrentTenantMemberApiTest
 * と同じ構成にして、Spring のコンテキストを共有する。
 */
// MockMvc の期待値は andExpect で書く。補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
// 2 つの操作の再送、キーの誤り、テナントの分離を一つのクラスで確かめるため、メソッドと static import が多い。
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
@SpringBootTest
@AutoConfigureMockMvc
@Import(SharedTestConfiguration.class)
@ExtendWith(CleanGeneratedTablesExtension.class)
class CurrentTenantMemberIdempotencyApiTest {

  /** 選んだテナントの所属の API。 */
  private static final String MEMBERS = "/api/current-tenant/members";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 所属の行を書く jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** テストの管理者が選ぶテナント。 */
  private String tenant;

  /** 同じテナントの MEMBER の利用者。 */
  private String member;

  @BeforeEach
  void registerTenantWithMember() {
    tenant = UUID.randomUUID().toString();
    member = UUID.randomUUID().toString();
    TestTenancy.member(dsl, member, UUID.fromString(tenant), "MEMBER");
  }

  @Test
  @DisplayName("役割を変えた後に同じキーと同じ本文で再送すると、409 でなく 204 を返し、役割の変更は 1 回だけ動く")
  void changeRoleReplayReturnsNoContent() throws Exception {
    final RequestPostProcessor key = IdempotencyKeys.header(IdempotencyKeys.next());

    changeRole(tenant, member, "ADMIN", 1, key).andExpect(status().isNoContent());
    changeRole(tenant, member, "ADMIN", 1, key).andExpect(status().isNoContent());

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(roleOf(member).value("ADMIN"));
  }

  @Test
  @DisplayName("所属を外した後に同じキーで再送すると、409 でなく 204 を返し、取り外しは 1 回だけ動く")
  void removeReplayReturnsNoContent() throws Exception {
    final RequestPostProcessor key = IdempotencyKeys.header(IdempotencyKeys.next());

    remove(tenant, member, 1, key).andExpect(status().isNoContent());
    remove(tenant, member, 1, key).andExpect(status().isNoContent());

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(jsonPath("$.items.length()").value(1));
  }

  @Test
  @DisplayName("役割の変更に使ったキーで、本文の違う変更か所属の取り外しを送ると、ペイロードの不一致の 400 を返し、所属を変えない")
  void differentPayloadReturnsBadRequest() throws Exception {
    final RequestPostProcessor key = IdempotencyKeys.header(IdempotencyKeys.next());
    changeRole(tenant, member, "ADMIN", 1, key).andExpect(status().isNoContent());

    expectIdempotencyProblem(
        changeRole(tenant, member, "MEMBER", 2, key), "idempotency-key-payload-mismatch");
    expectIdempotencyProblem(remove(tenant, member, 2, key), "idempotency-key-payload-mismatch");

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(roleOf(member).value("ADMIN"));
  }

  @Test
  @DisplayName("Idempotency-Key のない役割の変更と所属の取り外しは、入力の検証より先にキーなしの 400 を返し、所属を変えない")
  void missingKeyReturnsBadRequest() throws Exception {
    // 本文とクエリは検証で 400 になる形にし、キーの検査が先に動くことを確かめる。
    expectIdempotencyProblem(
        mockMvc.perform(
            asAdmin(put(MEMBERS + "/" + member), tenant)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")),
        "idempotency-key-missing");
    expectIdempotencyProblem(
        mockMvc.perform(asAdmin(delete(MEMBERS + "/" + member), tenant)),
        "idempotency-key-missing");

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(roleOf(member).value("MEMBER"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"8e03978e-40d5-43e8-bc93-6894a57f9324", "\"not-a-uuid\""})
  @DisplayName("引用符で囲んだ UUID でない Idempotency-Key は、形式の誤りの 400 を返し、所属を変えない")
  void malformedKeyReturnsBadRequest(final String value) throws Exception {
    final RequestPostProcessor malformed =
        request -> {
          request.addHeader(IdempotencyKey.HEADER, value);
          return request;
        };

    expectIdempotencyProblem(
        changeRole(tenant, member, "ADMIN", 1, malformed), "idempotency-key-invalid");
    expectIdempotencyProblem(remove(tenant, member, 1, malformed), "idempotency-key-invalid");

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(roleOf(member).value("MEMBER"));
  }

  @Test
  @DisplayName("同じ利用者が 2 つのテナントで同じキーと同じ利用者の取り外しを送ると、それぞれのテナントで 1 回ずつ動き、互いの記録を返さない")
  void tenantsDoNotShareKeysWithoutRowLevelSecurity() throws Exception {
    // tenancy のキーの表は RLS の外にあるので、キーの表の文が書くテナントだけで分かれる（ADR-126）。
    final String otherTenant = UUID.randomUUID().toString();
    TestTenancy.member(dsl, member, UUID.fromString(otherTenant), "MEMBER");
    final RequestPostProcessor key = IdempotencyKeys.header(IdempotencyKeys.next());

    remove(tenant, member, 1, key).andExpect(status().isNoContent());
    remove(otherTenant, member, 1, key).andExpect(status().isNoContent());
    remove(tenant, member, 1, key).andExpect(status().isNoContent());
    remove(otherTenant, member, 1, key).andExpect(status().isNoContent());

    for (final String removed : new String[] {tenant, otherTenant}) {
      listMembers(removed)
          .andExpect(jsonPath("$.lockNo").value(2))
          .andExpect(jsonPath("$.items.length()").value(1));
    }
  }

  /** 一覧の items から、指定した利用者の役割を指す期待値。 */
  private static JsonPathResultMatchers roleOf(final String userId) {
    return jsonPath("$.items[?(@.userId == '" + userId + "')].role");
  }

  private ResultActions changeRole(
      final String tenantId,
      final String userId,
      final String role,
      final long lockNo,
      final RequestPostProcessor key)
      throws Exception {
    return mockMvc.perform(
        asAdmin(put(MEMBERS + "/" + userId), tenantId)
            .with(key)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"" + role + "\",\"lockNo\":" + lockNo + "}"));
  }

  private ResultActions remove(
      final String tenantId, final String userId, final long lockNo, final RequestPostProcessor key)
      throws Exception {
    return mockMvc.perform(
        asAdmin(delete(MEMBERS + "/" + userId), tenantId)
            .param("lockNo", String.valueOf(lockNo))
            .with(key));
  }

  private ResultActions listMembers(final String tenantId) throws Exception {
    return mockMvc
        .perform(get(MEMBERS).with(inTenant(tenantId, "ADMIN")))
        .andExpect(status().isOk());
  }

  /** テストの利用者をテナントの管理者にし、CSRF token を付けた、キーのない要求にする。 */
  private static MockHttpServletRequestBuilder asAdmin(
      final MockHttpServletRequestBuilder request, final String tenantId) {
    return request.with(inTenant(tenantId, "ADMIN")).with(csrf());
  }

  /** Idempotency-Key の 400 の problem type で、detail を持たないことを確かめる。 */
  private static void expectIdempotencyProblem(final ResultActions actions, final String type)
      throws Exception {
    actions
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/" + type))
        .andExpect(jsonPath("$.detail").doesNotExist());
  }
}
