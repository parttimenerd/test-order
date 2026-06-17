package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class as safe for concurrent access from multiple threads. All public
 * methods may be invoked concurrently without external synchronization, though
 * compound operations (read-modify-write spanning multiple calls) may still
 * require caller-provided locking.
 *
 * <p>
 * Source-retention only; carries no runtime semantics.
 */
@Documented
@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ThreadSafe {
}
