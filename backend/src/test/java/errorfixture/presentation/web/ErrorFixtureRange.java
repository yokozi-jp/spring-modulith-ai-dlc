package errorfixture.presentation.web;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import jakarta.validation.constraintvalidation.SupportedValidationTarget;
import jakarta.validation.constraintvalidation.ValidationTarget;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 引数をまたぐ制約の検証用。最初の 2 つの int 引数が {@code from <= to} であること。 */
@Constraint(validatedBy = ErrorFixtureRange.Validator.class)
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface ErrorFixtureRange {

  /** 違反の文言。 */
  String message() default "from must not be greater than to";

  /** 検証グループ。 */
  Class<?>[] groups() default {};

  /** 付加情報。 */
  Class<? extends Payload>[] payload() default {};

  /** 引数の配列を検証する。 */
  @SupportedValidationTarget(ValidationTarget.PARAMETERS)
  final class Validator implements ConstraintValidator<ErrorFixtureRange, Object[]> {

    @Override
    public boolean isValid(final Object[] arguments, final ConstraintValidatorContext context) {
      return (int) arguments[0] <= (int) arguments[1];
    }
  }
}
