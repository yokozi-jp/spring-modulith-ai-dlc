package com.example.demo.tenancy.presentation.web;

import static com.example.demo.testkit.AppUsers.inTenant;
import static com.example.demo.testkit.AppUsers.selectedTenant;
import static com.example.demo.testkit.IdempotencyKeys.expectIdempotencyProblem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 選んだテナントの所属の更新の API が、Idempotency-Key の再送に 1 回目と同じ応答を返し、別の入力に使ったキーを 400 にすることを HTTP
 * の形で確かめる（ADR-126）。キーのヘッダーの検査は、操作によらない共通の処理なので OrderIdempotencyApiTest が確かめる。
 *
 * <p>{@code tenancy} のキーの表は RLS の外にあるので、別のテナントの同じキーに当たらないことも確かめる。CurrentTenantMemberApiTest
 * と同じ構成にして、Spring のコンテキストを共有する。
 */
// MockMvc の期待値は andExpect で書く。補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
// 2 つの操作の再送、ペイロードの不一致、テナントの分離を一つのクラスで確かめるため、static import が多い。
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.SignatureDeclareThrowsException",
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
        changeRole(tenant, member, "MEMBER", 2, key), 400, "idempotency-key-payload-mismatch");
    expectIdempotencyProblem(
        remove(tenant, member, 2, key), 400, "idempotency-key-payload-mismatch");

    listMembers(tenant)
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(roleOf(member).value("ADMIN"));
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

  /** 所属を書き足さずに、管理者として選んだテナントの所属を読む。 */
  private ResultActions listMembers(final String tenantId) throws Exception {
    return mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenantId, "ADMIN")))
        .andExpect(status().isOk());
  }

  /** テストの利用者をテナントの管理者にし、CSRF token を付けた、キーのない要求にする。 */
  private static MockHttpServletRequestBuilder asAdmin(
      final MockHttpServletRequestBuilder request, final String tenantId) {
    return request.with(inTenant(tenantId, "ADMIN")).with(csrf());
  }
}
