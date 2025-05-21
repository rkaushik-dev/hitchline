import type { Config } from '../../config/env.js';
import type { Clock, Durable, IdSource, Ports } from '../../app/ports.js';
import { fileStore } from './file.js';
import { memoryStore } from './memory.js';

/**
 * Opens whichever store the configuration asks for. Callers get the ports and,
 * when the store has anything to flush, a way to close it cleanly.
 */
export type OpenStore = Ports & Partial<Durable>;

export interface OpenOptions {
  readonly clock?: Clock;
  readonly ids?: IdSource;
  /** Pass false for a tool that only reads, so it can share a live journal. */
  readonly lock?: boolean;
}

export async function openStore(config: Config, options: OpenOptions = {}): Promise<OpenStore> {
  if (config.store === 'memory') {
    return memoryStore(options);
  }
  return fileStore({ path: config.storePath, ...options });
}

/**
 * Opens the store for a tool that only reads. It shares the journal with a
 * running server rather than refusing to start, which is what a status or an
 * export command wants.
 */
export async function openStoreForReading(
  config: Config,
  options: OpenOptions = {},
): Promise<OpenStore> {
  return openStore(config, { ...options, lock: false });
}

export async function closeStore(store: OpenStore): Promise<void> {
  if (typeof store.flush === 'function') await store.flush();
  if (typeof store.close === 'function') await store.close();
}
