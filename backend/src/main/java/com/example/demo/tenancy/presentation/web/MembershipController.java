package com.example.demo.tenancy.presentation.web;

import com.example.demo.shared.security.AppUserPrincipal;
import com.example.demo.tenancy.TenancyQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** ログインした利用者の所属の HTTP API。 */
@RestController
@RequestMapping(path = MembershipController.PATH, version = "1")
@Tag(name = "tenancy", description = "テナントと所属の API")
class MembershipController {

  /**
   * 所属の一覧のパス。テナントを選ぶ前にも呼べる。
   *
   * <p>mapping と {@code requestMatchers} に渡すパスのテンプレートで、URL ではない。
   */
  /* package */ static final String PATH = "/api/v{version}/memberships";

  /** 利用者の所属の参照。 */
  private final TenancyQueries tenancyQueries;

  /** 依存を受け取る。 */
  /* package */ MembershipController(final TenancyQueries tenancyQueries) {
    this.tenancyQueries = tenancyQueries;
  }

  /**
   * ログインした利用者の所属の一覧を返す。
   *
   * <p>一覧はテナントの名前の順に items で包んで返す。テナントを選ぶ前にも呼べる。所属がなければ空の items を返す。
   *
   * @param user ログインした利用者
   * @return 所属の一覧
   */
  @Operation(operationId = "listMemberships")
  @GetMapping
  /* package */ MembershipListResponse search(
      @AuthenticationPrincipal(errorOnInvalidType = true) final AppUserPrincipal user) {
    return new MembershipListResponse(
        tenancyQueries.memberships(user.userId()).stream().map(MembershipResponse::from).toList());
  }
}
