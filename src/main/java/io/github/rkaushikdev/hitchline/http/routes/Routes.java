package io.github.rkaushikdev.hitchline.http.routes;

import io.github.rkaushikdev.hitchline.app.Ports;
import io.github.rkaushikdev.hitchline.http.HttpApp;

/**
 * Every route the api serves, in one call. Order does not matter to the router, so they are listed
 * the way somebody reading the api would meet them.
 */
public final class Routes {

    private Routes() {}

    /** Registers every route group except the audit trail, which reads the application's own log. */
    public static void registerAll(HttpApp app, Ports ports) {
        HabitRoutes.register(app, ports);
        CheckInRoutes.register(app, ports);
        RosterRoutes.register(app, ports);
        AdherenceRoutes.register(app, ports);
        NudgeRoutes.register(app, ports);
        CrewRoutes.register(app, ports);
        CompareRoutes.register(app, ports);
        SyncRoutes.register(app, ports);
    }
}
