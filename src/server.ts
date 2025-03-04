import { readConfig } from './config/env.js';
import { closeStore, openStore } from './infra/store/open.js';
import { createShutdown, onSignals } from './infra/shutdown.js';
import { buildApp } from './http/app.js';

/**
 * The process entry point. It reads the environment, opens the store the
 * configuration names, serves the api and closes both on a signal.
 */
export async function main(): Promise<void> {
  const config = readConfig();
  const store = await openStore(config);
  const app = buildApp({ config, ports: store });
  const shutdown = createShutdown();

  shutdown.add('http', () => app.close());
  shutdown.add('store', () => closeStore(store));

  onSignals(process, shutdown, (report) => {
    if (report.failed.length > 0) {
      app.log.error({ failed: report.failed }, 'shutdown had trouble');
    }
    process.exit(report.failed.length === 0 ? 0 : 1);
  });

  await app.listen({ host: config.host, port: config.port });
  app.log.info({ store: config.store, path: config.storePath }, 'hitchline is up');
}

await main();
