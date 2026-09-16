package com.mfec.dac.auth;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a resource or method as requiring a valid session token.
 *
 * <p>Name binding rather than a global filter, so that a new endpoint is
 * unauthenticated only when someone wrote no annotation at all — a mistake that
 * shows up in review as a missing line, not as a silently inherited default.
 * Login itself and the version probe are the endpoints that legitimately carry
 * no annotation.
 *
 * <p>Listing roles narrows it further: the caller must hold at least one of
 * them, with PLATFORM_ADMIN always sufficient. An empty list means any
 * authenticated caller.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Secured {

  /** Application roles, any one of which admits the caller (FR-2.6). */
  String[] value() default {};
}
