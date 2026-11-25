package archfixture.violating.ordering.presentation.web;

import org.springframework.web.bind.annotation.ControllerAdvice;

/** 違反：httpErrorsAreMappedOnlyInErrorPresentationWeb（機能モジュールが ControllerAdvice を置く）。 */
@ControllerAdvice
class OrderStatusAdvice {}
