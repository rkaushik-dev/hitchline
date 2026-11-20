package io.github.rkaushikdev.hitchline.domain.crew;

/**
 * Somebody asking to read another person's adherence.
 *
 * @param memberId who is asking
 * @param role what they are in their crew
 */
public record Viewer(String memberId, CrewRole role) {}
