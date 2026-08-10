package eu.wohlben.qits.eventstream;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A TEST FIXTURE mirroring the real annotation's name — see {@link CausedRow}. It mirrors the CLASS
 * retention on purpose: the self-test doubles as the proof that ArchUnit reads class-retention
 * annotations from bytecode, which is what lets the real one stay invisible at runtime.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.CLASS)
public @interface Uncaused {}
