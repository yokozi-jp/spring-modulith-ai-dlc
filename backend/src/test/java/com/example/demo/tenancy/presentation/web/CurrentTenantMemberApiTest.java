package com.example.demo.tenancy.presentation.web;

import static com.example.demo.testkit.AppUsers.inTenant;
import static com.example.demo.testkit.AppUsers.selectedTenant;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.demo.tenancy.TestTenancy;
import com.example.demo.testkit.AppUsers;
import com.example.demo.testkit.CleanGeneratedTablesExtension;
import com.example.demo.testkit.ContractCase;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.bind.annotation.RequestMethod;

/**
 * 選んだテナントの所属の API を、管理者だけが呼べること、別のテナントに届かないこと、変更が次のリクエストから効くことを、実際の Security filter chain で検証する。
 */
// MockMvc の期待値は andExpect で書く。一つの API の契約を一つのクラスで確かめるため、メソッドと static import が多い。
// 補助のメソッドは、Exception を宣言する MockMvc.perform を呼ぶ。
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
class CurrentTenantMemberApiTest {

  /** 選んだテナントの所属の API。 */
  private static final String MEMBERS = "/api/current-tenant/members";

  /** 管理者でない利用者への 403 の type。専用の type を持たない。 */
  private static final String ABOUT_BLANK = "about:blank";

  /** テナントを選ぶ前の 403 の type。 */
  private static final String TENANT_NOT_SELECTED = "/problems/tenant-not-selected";

  /** 実際の Spring MVC と Security filter chain を通すクライアント。 */
  @Autowired private MockMvc mockMvc;

  /** 所属の行を書く jOOQ のコンテキスト。 */
  @Autowired private DSLContext dsl;

  /** テストの管理者（{@link AppUsers#USER_ID}）が選ぶテナント。 */
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
  @DisplayName("管理者は、選んだテナントの所属の一覧とロック番号を読める。リクエストの値でテナントを変えられない")
  @ContractCase(method = RequestMethod.GET, path = MEMBERS, status = 200)
  void adminListsMembersOfTheSelectedTenant() throws Exception {
    final String otherTenantUser = UUID.randomUUID().toString();
    final String otherTenant = UUID.randomUUID().toString();
    TestTenancy.member(dsl, otherTenantUser, UUID.fromString(otherTenant), "MEMBER");

    mockMvc
        .perform(get(MEMBERS).param("tenantId", otherTenant).with(inTenant(tenant, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[*].userId", containsInAnyOrder(member, AppUsers.USER_ID)))
        .andExpect(jsonPath("$.items[?(@.userId == '" + member + "')].role").value("MEMBER"))
        .andExpect(
            jsonPath("$.items[?(@.userId == '" + AppUsers.USER_ID + "')].role").value("ADMIN"));
  }

  @Test
  @DisplayName("管理者でない利用者は、所属の一覧の読み取り、役割の変更、所属の取り外しのどれも 403 になり、所属は変わらない")
  void memberIsForbiddenFromEveryOperation() throws Exception {
    final RequestPostProcessor asMember = inTenant(tenant, "MEMBER");

    mockMvc
        .perform(get(MEMBERS).with(asMember))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(ABOUT_BLANK));
    changeRole(member, "ADMIN", 1, asMember)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(ABOUT_BLANK));
    remove(member, 1, asMember)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(ABOUT_BLANK));

    mockMvc
        .perform(get(MEMBERS).with(inTenant(tenant, "ADMIN")))
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(jsonPath("$.items[?(@.userId == '" + member + "')].role").value("MEMBER"));
  }

  @Test
  @DisplayName("管理者が役割を変えると 204 を返し、一覧の役割が変わりロック番号が進む")
  @ContractCase(method = RequestMethod.PUT, path = MEMBERS + "/{userId}", status = 204)
  void adminChangesARole() throws Exception {
    changeRole(member, "ADMIN", 1, inTenant(tenant, "ADMIN")).andExpect(status().isNoContent());

    mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenant, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(jsonPath("$.items[?(@.userId == '" + member + "')].role").value("ADMIN"));
  }

  @Test
  @DisplayName("管理者が所属を外すと 204 を返し、一覧から消える")
  @ContractCase(method = RequestMethod.DELETE, path = MEMBERS + "/{userId}", status = 204)
  void adminRemovesAMember() throws Exception {
    remove(member, 1, inTenant(tenant, "ADMIN")).andExpect(status().isNoContent());

    mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenant, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lockNo").value(2))
        .andExpect(jsonPath("$.items.length()").value(1))
        .andExpect(jsonPath("$.items[0].userId").value(AppUsers.USER_ID));
  }

  @Test
  @DisplayName("管理者が自分の役割を MEMBER に下げると、同じリクエストの権限のままでも、次のリクエストから 403 になる")
  void demotionAppliesOnTheNextRequest() throws Exception {
    TestTenancy.member(dsl, UUID.randomUUID().toString(), UUID.fromString(tenant), "ADMIN");
    changeRole(AppUsers.USER_ID, "MEMBER", 1, inTenant(tenant, "ADMIN"))
        .andExpect(status().isNoContent());

    // 認証が持つ権限は ADMIN のままで、DB の所属の役割だけが MEMBER になっている。
    mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenant, "ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(ABOUT_BLANK));
  }

  @Test
  @DisplayName("管理者が自分の所属を外すと、次のリクエストから、テナントを選ぶ前の 403 になる")
  void removalAppliesOnTheNextRequest() throws Exception {
    TestTenancy.member(dsl, UUID.randomUUID().toString(), UUID.fromString(tenant), "ADMIN");
    remove(AppUsers.USER_ID, 1, inTenant(tenant, "ADMIN")).andExpect(status().isNoContent());

    mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenant, "ADMIN")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.type").value(TENANT_NOT_SELECTED));
  }

  @Test
  @DisplayName("最後の管理者の役割を下げる操作と所属を外す操作は 422 になり、所属は変わらない")
  @ContractCase(method = RequestMethod.PUT, path = MEMBERS + "/{userId}", status = 422)
  @ContractCase(method = RequestMethod.DELETE, path = MEMBERS + "/{userId}", status = 422)
  void lastAdminCannotBeDemotedOrRemoved() throws Exception {
    final RequestPostProcessor asAdmin = inTenant(tenant, "ADMIN");

    changeRole(AppUsers.USER_ID, "MEMBER", 1, asAdmin).andExpect(status().isUnprocessableContent());
    remove(AppUsers.USER_ID, 1, asAdmin).andExpect(status().isUnprocessableContent());

    mockMvc
        .perform(get(MEMBERS).with(selectedTenant(tenant, "ADMIN")))
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(
            jsonPath("$.items[?(@.userId == '" + AppUsers.USER_ID + "')].role").value("ADMIN"));
  }

  @Test
  @DisplayName("所属していない利用者と、別のテナントだけに所属する利用者は 404 になり、別のテナントの所属は変わらない")
  @ContractCase(method = RequestMethod.PUT, path = MEMBERS + "/{userId}", status = 404)
  @ContractCase(method = RequestMethod.DELETE, path = MEMBERS + "/{userId}", status = 404)
  void usersOutsideTheSelectedTenantAreNotFound() throws Exception {
    final String otherTenantUser = UUID.randomUUID().toString();
    final String otherTenant = UUID.randomUUID().toString();
    TestTenancy.member(dsl, otherTenantUser, UUID.fromString(otherTenant), "ADMIN");
    final RequestPostProcessor asAdmin = inTenant(tenant, "ADMIN");

    changeRole(otherTenantUser, "MEMBER", 1, asAdmin).andExpect(status().isNotFound());
    remove(otherTenantUser, 1, asAdmin).andExpect(status().isNotFound());
    changeRole(UUID.randomUUID().toString(), "MEMBER", 1, asAdmin).andExpect(status().isNotFound());
    remove(UUID.randomUUID().toString(), 1, asAdmin).andExpect(status().isNotFound());

    mockMvc
        .perform(get(MEMBERS).with(inTenant(otherTenant, "ADMIN")))
        .andExpect(
            jsonPath("$.items[?(@.userId == '" + otherTenantUser + "')].role").value("ADMIN"));
  }

  @Test
  @DisplayName("古いロック番号での役割の変更と所属の取り外しは 409 になり、所属は変わらない")
  @ContractCase(method = RequestMethod.PUT, path = MEMBERS + "/{userId}", status = 409)
  @ContractCase(method = RequestMethod.DELETE, path = MEMBERS + "/{userId}", status = 409)
  void staleLockNoIsConflict() throws Exception {
    final RequestPostProcessor asAdmin = inTenant(tenant, "ADMIN");

    changeRole(member, "ADMIN", 2, asAdmin).andExpect(status().isConflict());
    remove(member, 2, asAdmin).andExpect(status().isConflict());

    mockMvc
        .perform(get(MEMBERS).with(asAdmin))
        .andExpect(jsonPath("$.lockNo").value(1))
        .andExpect(jsonPath("$.items[?(@.userId == '" + member + "')].role").value("MEMBER"));
  }

  @Test
  @DisplayName("役割が ADMIN と MEMBER のどちらでもない値と、1 未満のロック番号は 400 になる")
  @ContractCase(method = RequestMethod.PUT, path = MEMBERS + "/{userId}", status = 400)
  @ContractCase(method = RequestMethod.DELETE, path = MEMBERS + "/{userId}", status = 400)
  void invalidInputIsBadRequest() throws Exception {
    final RequestPostProcessor asAdmin = inTenant(tenant, "ADMIN");

    changeRole(member, "OWNER", 1, asAdmin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-error"))
        .andExpect(jsonPath("$.errors[0].pointer").value("/role"));
    changeRole(member, "ADMIN", 0, asAdmin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].pointer").value("/lockNo"));
    remove(member, 0, asAdmin)
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.type").value("/problems/validation-error"));
  }

  private ResultActions changeRole(
      final String userId, final String role, final long lockNo, final RequestPostProcessor user)
      throws Exception {
    return mockMvc.perform(
        put(MEMBERS + "/" + userId)
            .with(user)
            .with(csrf())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"role\":\"" + role + "\",\"lockNo\":" + lockNo + "}"));
  }

  private ResultActions remove(
      final String userId, final long lockNo, final RequestPostProcessor user) throws Exception {
    return mockMvc.perform(
        delete(MEMBERS + "/" + userId)
            .param("lockNo", String.valueOf(lockNo))
            .with(user)
            .with(csrf()));
  }
}
