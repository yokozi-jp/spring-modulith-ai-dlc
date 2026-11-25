package archfixture.violating.ordering.domain.model;

import archfixture.violating.ordering.application.AdminOnly;

/**
 * 違反：methodSecurityAnnotationsAreOnlyOnApplicationClasses（@PreAuthorize をメタアノテーションに持つ注釈を domain
 * に付ける）。
 */
public class OrderArchive {

  /** 管理者だけに、注文の保管を許す。 */
  @AdminOnly
  public void archive() {
    // 注釈だけを検査するため、何もしない。
  }
}
