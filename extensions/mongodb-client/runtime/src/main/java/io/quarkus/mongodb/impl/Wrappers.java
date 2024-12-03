package io.quarkus.mongodb.impl;

import java.time.Duration;
import java.util.List;

import org.reactivestreams.Publisher;

import io.quarkus.mongodb.runtime.ReactiveBatchingConfig;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import mutiny.zero.flow.adapters.AdaptersToFlow;

class Wrappers {

    private Wrappers() {
        // Avoid direct instantiation.
    }

    static <T> Uni<T> toUni(Publisher<T> publisher) {
        Context context = Vertx.currentContext();
        Uni<T> uni = Uni.createFrom().publisher(AdaptersToFlow.publisher(publisher));
        if (context != null) {
            return uni.emitOn(command -> context.runOnContext(x -> command.run()));
        }
        return uni;
    }

    static <T> Multi<T> toMulti(Publisher<T> publisher, ReactiveBatchingConfig batchingConfig) {
        Context context = Vertx.currentContext();
        if (context != null) {
            if (batchingConfig != null && batchingConfig.enabled) {
                return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher))
                        .group().intoLists().of(batchingConfig.batchSize, Duration.ofMillis(batchingConfig.maxDelay))
                        .emitOn(command -> context.runOnContext(x -> command.run()))
                        .onItem().transformToMultiAndConcatenate(list -> Multi.createFrom().iterable(list));
            } else {
                return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher))
                        .emitOn(command -> context.runOnContext(x -> command.run()));
            }
        } else {
            return Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher));
        }
    }

    static <T> Uni<List<T>> toUniOfList(Publisher<T> publisher) {
        Context context = Vertx.currentContext();
        Uni<List<T>> uni = Multi.createFrom().publisher(AdaptersToFlow.publisher(publisher))
                .collect().asList();

        if (context != null) {
            return uni.emitOn(command -> context.runOnContext(x -> command.run()));
        }
        return uni;
    }
}
