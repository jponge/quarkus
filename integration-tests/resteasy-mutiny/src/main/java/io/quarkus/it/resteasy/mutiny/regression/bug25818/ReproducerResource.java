package io.quarkus.it.resteasy.mutiny.regression.bug25818;

import javax.inject.Inject;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import org.jboss.logging.Logger;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Vertx;

@Path("/reproducer/25818")
public class ReproducerResource {

    private final Logger logger = Logger.getLogger(ReproducerResource.class);

    @Inject
    BlockingService service;

    private void addToContext() {
        Vertx.currentContext().putLocal("hello-target", "you");
    }

    @GET
    @Path("/worker-pool")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> workerPool() {
        logger.info("worker pool endpoint");
        addToContext();
        return Uni.createFrom()
                .item(service::getBlocking)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @GET
    @Path("/default-executor")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> defaultExecutor() {
        logger.info("default executor endpoint");
        addToContext();
        return Uni.createFrom()
                .item(service::getBlocking)
                .runSubscriptionOn(Infrastructure.getDefaultExecutor());
    }
}
