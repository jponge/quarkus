package io.quarkus.vertx.utils;

import io.vertx.ext.web.RoutingContext;

/**
 * A context giving access to Vert.x {@link RoutingContext} and to configuration values
 * for a {@link VertxInputStream}.
 */
public interface VertxInputContext {

    /**
     * @return the Vert.x routing context
     */
    RoutingContext getRoutingContext();

    /**
     * @return the read timeout in milliseconds
     */
    long getTimeout();

    /**
     * Returns the maximum allowed request size in bytes, or {@code -1} for unlimited.
     *
     * @return the maximum request size, or -1 if unlimited
     */
    long getMaxRequestSize();

    /**
     * @return the current continue state
     */
    ContinueState getContinueState();

    /**
     * Sets the continue state.
     *
     * @param state the new continue state
     */
    void setContinueState(ContinueState state);

    enum ContinueState {
        NONE,
        REQUIRED,
        SENT
    }
}
