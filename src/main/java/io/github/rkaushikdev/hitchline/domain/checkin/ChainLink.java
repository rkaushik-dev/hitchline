package io.github.rkaushikdev.hitchline.domain.checkin;

/**
 * One entry of the hash chain.
 *
 * @param entry the check in
 * @param digest its digest, folded onto every entry before it
 */
public record ChainLink(CheckIn entry, String digest) {}
