package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as <b>not</b> safe for concurrent access. Callers must ensure
 * single-threaded access per instance, or provide their own external
 * synchronization.
 *
 * <p>
 * Source-retention only; carries no runtime semantics.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface NotThreadSafe {
}
