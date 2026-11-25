package errorfixture.application;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/** 管理者だけに許す操作。役割の不足を method security が拒否することを確かめるためだけに使う。 */
@Service
public class AdminOnlyCommandHandler {

  /** 管理者の要求だけを受け、何もしない。 */
  @PreAuthorize("hasRole('ADMIN')")
  public void handle() {
    // 拒否されるかどうかだけを確かめるため、何もしない。
  }
}
