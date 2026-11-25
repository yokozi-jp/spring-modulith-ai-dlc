package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.failure.BusinessRuleViolationException;
import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.shared.security.TenantNotSelectedException;
import com.example.demo.tenancy.MembershipSummary;
import com.example.demo.tenancy.TenancyQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.extensions.Extension;
import io.swagger.v3.oas.annotations.extensions.ExtensionProperty;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** セッションで選んだテナントの HTTP API。 */
@RestController
@RequestMapping(CurrentTenantController.PATH)
@Tag(name = "tenancy", description = "テナントと所属の API")
class CurrentTenantController {

  /** 選んだテナントのパス。PUT だけは、テナントを選ぶ前にも呼べる。 */
  /* package */ static final String PATH = "/api/current-tenant";

  /** 利用者の所属の参照。 */
  private final TenancyQueries tenancyQueries;

  /** セッションで選んだテナント。 */
  private final TenantSession tenantSession;

  /** 依存を受け取る。 */
  /* package */ CurrentTenantController(
      final TenancyQueries tenancyQueries, final TenantSession tenantSession) {
    this.tenancyQueries = tenancyQueries;
    this.tenantSession = tenantSession;
  }

  /**
   * セッションで選んだテナントを返す。
   *
   * <p>テナントを選ぶ前は、type が /problems/tenant-not-selected の 403 を返す。
   *
   * @param user ログインした利用者
   * @param authentication ログインした利用者の認証
   * @return 選んだテナントへの所属
   */
  @Operation(operationId = "findCurrentTenant")
  @GetMapping
  /* package */ MembershipResponse details(
      @AuthenticationPrincipal(errorOnInvalidType = true) final AppUserPrincipal user,
      final Authentication authentication) {
    return TenantSession.selectedTenant(authentication)
        .flatMap(tenant -> tenancyQueries.membership(user.userId(), tenant.tenantId()))
        .map(MembershipResponse::from)
        .orElseThrow(() -> new TenantNotSelectedException("tenant not selected"));
  }

  /**
   * 所属するテナントを選び、そのテナントで業務の API を使えるようにする。
   *
   * <p>成功するとセッションの ID が変わる。所属していないテナントを指定したとき、またはすでにテナントを選んでいるときは 422 を返す。テナントを切り替えるには、ログインし直す。
   *
   * @param body 選ぶテナント
   * @param user ログインした利用者
   * @param authentication ログインした利用者の認証
   * @param request セッションを持つリクエスト
   * @param response セッションの Cookie を返す応答
   * @return 本文のない 204 の応答
   */
  // テナントを選ぶ前の操作なので、Idempotency-Key を求めない（ADR-126）。
  @Operation(
      operationId = "selectTenant",
      extensions =
          @Extension(properties = @ExtensionProperty(name = "x-idempotency-key", value = "none")))
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PutMapping
  /* package */ ResponseEntity<Void> select(
      @Valid @RequestBody final SelectTenantRequest body,
      @AuthenticationPrincipal(errorOnInvalidType = true) final AppUserPrincipal user,
      final Authentication authentication,
      final HttpServletRequest request,
      final HttpServletResponse response) {
    if (TenantSession.selectedTenant(authentication).isPresent()) {
      throw new BusinessRuleViolationException("tenant already selected; sign in again to switch");
    }
    final MembershipSummary membership =
        tenancyQueries
            .membership(user.userId(), body.tenantId().toString())
            .orElseThrow(() ->
                new BusinessRuleViolationException(
                    "not a member of the tenant: tenantId=" + body.tenantId()));
    tenantSession.select(request, response, authentication, membership);
    return ResponseEntity.noContent().build();
  }
}
