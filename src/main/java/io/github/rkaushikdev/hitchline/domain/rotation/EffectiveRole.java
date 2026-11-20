package io.github.rkaushikdev.hitchline.domain.rotation;

/**
 * The role a day actually has, and whether the pattern or an override decided it.
 *
 * @param role the role of the day
 * @param source what decided it
 */
public record EffectiveRole(DayRole role, RoleSource source) {}
