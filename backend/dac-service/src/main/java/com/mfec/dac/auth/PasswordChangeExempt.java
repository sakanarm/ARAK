package com.mfec.dac.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link Secured} endpoint that a caller who still has to choose a new
 * password may reach.
 *
 * <p>Everything else refuses their token with 403 until the password is
 * changed. Only reading one's own session and changing the password carry it:
 * an account whose password an administrator typed is not yet the holder's
 * account, and it should not read data or change anything else until it is.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PasswordChangeExempt {}
