package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.TenantNotSelectedException;
import com.example.demo.tenancy.TenancyQueries;
import com.example.demo.tenancy.application.ChangeMemberRoleCommandHandler;
import com.example.demo.tenancy.application.RemoveMemberCommandHandler;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * セッションで選んだテナントの所属の HTTP API。テナントの管理者だけが呼べる。
 *
 * <p>対象のテナントは、リクエストの値ではなく、セッションで選んだテナントから取る。管理者のロールの判定は {@link TenantSecurityCustomizer} が行う。
 */
@RestController
@RequestMapping(CurrentTenantMemberController.PATH)
@Tag(name = "tenancy", description = "テナントと所属の API")
class CurrentTenantMemberController {

  /** 選んだテナントの所属の一覧のパス。 */
  /* package */ static final String PATH = "/api/current-tenant/members";

  /** 選んだテナントの所属の参照。 */
  private final TenancyQueries tenancyQueries;

  /** 所属の役割を変える CommandHandler。 */
  private final ChangeMemberRoleCommandHandler changeMemberRole;

  /** 所属を外す CommandHandler。 */
  private final RemoveMemberCommandHandler removeMember;

  /** 依存を受け取る。 */
  /* package */ CurrentTenantMemberController(
      final TenancyQueries tenancyQueries,
      final ChangeMemberRoleCommandHandler changeMemberRole,
      final RemoveMemberCommandHandler removeMember) {
    this.tenancyQueries = tenancyQueries;
    this.changeMemberRole = changeMemberRole;
    this.removeMember = removeMember;
  }

  /**
   * 選んだテナントに所属している利用者の一覧を返す。
   *
   * <p>一覧は所属した順に items で包んで返す。lockNo は変更の操作で送り返す。管理者以外には 403 を返す。
   *
   * @param authentication ログインした利用者の認証
   * @return 所属の一覧
   */
  @Operation(operationId = "listTenantMembers")
  @GetMapping
  /* package */ TenantMembersDetailsResponse search(final Authentication authentication) {
    final String tenantId = selectedTenantId(authentication);
    // TenantSelectionFilter が所属を確かめた後なので、選んだテナントは必ずある。なければ実装の不備として 500 にする。
    return tenancyQueries
        .findMembers(tenantId)
        .map(TenantMembersDetailsResponse::from)
        .orElseThrow(
            () -> new IllegalStateException("selected tenant not found: tenantId=" + tenantId));
  }

  /**
   * 所属している利用者の役割を変える。
   *
   * <p>変更は、その利用者の次のリクエストから効く。管理者以外には 403、最後の管理者を下げると 422 を返す。
   *
   * @param userId 役割を変える利用者の ID
   * @param request 新しい役割と、画面が読んだテナントのロック番号
   * @param authentication ログインした利用者の認証
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "changeTenantMemberRole")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @PutMapping("/{userId}")
  /* package */ ResponseEntity<Void> changeRole(
      @PathVariable final UUID userId,
      @Valid @RequestBody final ChangeMemberRoleRequest request,
      final Authentication authentication) {
    changeMemberRole.handle(request.toCommand(selectedTenantId(authentication), userId));
    return ResponseEntity.noContent().build();
  }

  /**
   * 所属している利用者を外す。
   *
   * <p>外した利用者は、次のリクエストから業務の API を呼べない。管理者以外には 403、最後の管理者を外すと 422 を返す。
   *
   * @param userId 所属を外す利用者の ID
   * @param lockNo 画面が読んだテナントのロック番号
   * @param authentication ログインした利用者の認証
   * @return 本文のない 204 の応答
   */
  @Operation(operationId = "removeTenantMember")
  @ApiResponse(responseCode = "204")
  @ApiResponse(responseCode = "404", ref = "#/components/responses/NotFoundProblem")
  @ApiResponse(responseCode = "409", ref = "#/components/responses/ConflictProblem")
  @ApiResponse(responseCode = "422", ref = "#/components/responses/UnprocessableContentProblem")
  @DeleteMapping("/{userId}")
  /* package */ ResponseEntity<Void> remove(
      @PathVariable final UUID userId,
      @RequestParam @Min(1) final long lockNo,
      final Authentication authentication) {
    removeMember.handle(
        new RemoveMemberRequest(lockNo).toCommand(selectedTenantId(authentication), userId));
    return ResponseEntity.noContent().build();
  }

  /** セッションで選んだテナントの ID。{@link TenantSelectionFilter} が選ぶ前を拒否するため、ここで空になるのは実装の不備である。 */
  private static String selectedTenantId(final Authentication authentication) {
    return TenantSession.selectedTenant(authentication)
        .map(TenantMembershipAuthority::tenantId)
        .orElseThrow(() -> new TenantNotSelectedException("tenant not selected"));
  }
}
