/**
 * 業務上の失敗の例外を置く（ADR-061）。見つからないことを表す {@link com.example.demo.shared.failure.NotFoundException}
 * と、業務規則の違反を表す {@link com.example.demo.shared.failure.BusinessRuleViolationException} である。
 *
 * <p>Domain、Application、Presentation、Infrastructure のどの層からも使ってよい。業務の概念を置かず、Spring や HTTP に依存しない。
 */
@NamedInterface("failure")
@NullMarked
package com.example.demo.shared.failure;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
