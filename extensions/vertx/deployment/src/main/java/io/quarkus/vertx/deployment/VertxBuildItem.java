package io.quarkus.vertx.deployment;

import java.util.function.Supplier;

import io.quarkus.builder.item.SimpleBuildItem;
import io.vertx.core.Vertx;

public final class VertxBuildItem extends SimpleBuildItem {

    private final Supplier<Vertx> vertx;

    public VertxBuildItem(Supplier<Vertx> vertx) {
        this.vertx = vertx;
    }

    public Supplier<Vertx> getVertx() {
        return vertx;
    }

}
