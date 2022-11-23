package io.quarkus.mutiny.deployment.test;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

public class LegacyPublisherImplementation implements Publisher<Integer> {

    @Override
    public void subscribe(Subscriber<? super Integer> subscriber) {
        subscriber.onSubscribe(new Subscription() {
            @Override
            public void request(long l) {

            }

            @Override
            public void cancel() {

            }
        });
        subscriber.onComplete();
    }
}
