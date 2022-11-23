package io.quarkus.mutiny.deployment.test;

import java.io.IOException;

import javax.enterprise.context.ApplicationScoped;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;

@ApplicationScoped
public class BeanUsingMutiny {

    public Uni<String> greeting() {
        return Uni.createFrom().item(() -> "hello")
                .emitOn(Infrastructure.getDefaultExecutor());
    }

    public Multi<String> stream() {
        return Multi.createFrom().items("hello", "world")
                .emitOn(Infrastructure.getDefaultExecutor());
    }

    public Uni<String> droppedException() {
        return Uni.createFrom()
                .<String> emitter(uniEmitter -> {
                    // Do not emit anything
                })
                .onCancellation().call(() -> Uni.createFrom().failure(new IOException("boom")));
    }

    public Uni<String> loggingOperator() {
        return Uni.createFrom().item("hello")
                .onItem().transform(String::toUpperCase)
                .log("check");
    }
}
