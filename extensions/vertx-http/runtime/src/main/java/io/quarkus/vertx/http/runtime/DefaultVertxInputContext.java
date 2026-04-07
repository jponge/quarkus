package io.quarkus.vertx.http.runtime;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.quarkus.vertx.utils.VertxInputContext;
import io.vertx.ext.web.RoutingContext;

/**
 * Default {@link VertxInputContext} implementation that reads max request size from
 * the {@link RoutingContext} data map (set by {@link VertxHttpRecorder}) and manages
 * HTTP 100-continue state internally.
 */
public class DefaultVertxInputContext implements VertxInputContext {

    private static final String CONTINUE = "100-continue";

    private final RoutingContext routingContext;
    private final long timeout;
    private final long maxRequestSize;
    private ContinueState continueState;

    public DefaultVertxInputContext(RoutingContext routingContext, long timeout) {
        this.routingContext = routingContext;
        this.timeout = timeout;
        Long limitObj = routingContext.get(VertxHttpRecorder.MAX_REQUEST_SIZE_KEY);
        this.maxRequestSize = (limitObj == null) ? -1 : limitObj;

        String expect = routingContext.request().getHeader(HttpHeaderNames.EXPECT);
        if (expect != null && expect.equalsIgnoreCase(CONTINUE)) {
            this.continueState = ContinueState.REQUIRED;
        } else {
            this.continueState = ContinueState.NONE;
        }
    }

    @Override
    public RoutingContext getRoutingContext() {
        return routingContext;
    }

    @Override
    public long getTimeout() {
        return timeout;
    }

    @Override
    public long getMaxRequestSize() {
        return maxRequestSize;
    }

    @Override
    public ContinueState getContinueState() {
        return continueState;
    }

    @Override
    public void setContinueState(ContinueState state) {
        this.continueState = state;
    }
}
