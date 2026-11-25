package archfixture.violating.ordering.presentation.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 違反：methodSecurityAnnotationsAreOnlyOnApplicationClasses（Controller に @PreAuthorize を付ける）。 */
@RestController
@RequestMapping("/api/orders")
class OrderPurgeController {

  /** 管理者だけに、すべての注文の削除を許す。 */
  @PreAuthorize("hasRole('ADMIN')")
  @DeleteMapping
  /* package */ ResponseEntity<Void> purge() {
    return ResponseEntity.noContent().build();
  }
}
