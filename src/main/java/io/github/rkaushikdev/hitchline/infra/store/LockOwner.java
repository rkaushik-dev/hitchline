package io.github.rkaushikdev.hitchline.infra.store;

/**
 * Who holds the writer lock, as the lock file records it.
 *
 * @param pid the holding process
 * @param host the machine it runs on
 * @param at when it took the lock, as an ISO 8601 timestamp
 */
public record LockOwner(long pid, String host, String at) {

    /** This process, taking the lock at {@code nowIso}. */
    public static LockOwner here(String nowIso) {
        String host = System.getenv("HOSTNAME");
        return new LockOwner(ProcessHandle.current().pid(), host == null ? "local" : host, nowIso);
    }
}
