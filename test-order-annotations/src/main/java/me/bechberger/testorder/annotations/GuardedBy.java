package me.bechberger.testorder.annotations;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Documents the lock that must be held when accessing the annotated field or
 * method. The {@link #value()} names the lock — typically {@code "this"}, the
 * name of an instance field that is itself a lock, or a class name for static
 * locks.
 *
 * <p>
 * Class-retention so it can be inspected by static analysis tools but adds no
 * runtime overhead.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface GuardedBy {
	/** Name of the lock that guards the annotated element. */
	String value();
}
