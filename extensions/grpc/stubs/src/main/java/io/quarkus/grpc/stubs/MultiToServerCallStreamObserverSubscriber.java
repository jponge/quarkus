package io.quarkus.grpc.stubs;

import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicLong;

import io.grpc.stub.ServerCallStreamObserver;
import io.smallrye.mutiny.subscription.MultiSubscriber;

public class MultiToServerCallStreamObserverSubscriber<O> implements MultiSubscriber<O> {

    private final ServerCallStreamObserver<O> streamObserver;
    private final long prefetch;

    private Flow.Subscription upstream;
    private final AtomicLong demand = new AtomicLong();

    public MultiToServerCallStreamObserverSubscriber(ServerCallStreamObserver<O> streamObserver, long prefetch) {
        this.streamObserver = streamObserver;
        this.prefetch = prefetch;
    }

    @Override
    public void onSubscribe(Flow.Subscription subscription) {
        this.upstream = subscription;
        streamObserver.setOnCancelHandler(upstream::cancel);
        streamObserver.setOnCloseHandler(upstream::cancel);
        streamObserver.setOnReadyHandler(this::observerIsReady);
    }

    private void observerIsReady() {
        if (streamObserver.isReady() && demand.get() == 0L) {
            fetchMoreFromUpstream();
        }
    }

    private void fetchMoreFromUpstream() {
        demand.addAndGet(prefetch);
        upstream.request(prefetch);
    }

    @Override
    public void onItem(O item) {
        if (demand.decrementAndGet() == 0L && streamObserver.isReady()) {
            fetchMoreFromUpstream();
        }
        streamObserver.onNext(item);
    }

    @Override
    public void onFailure(Throwable failure) {
        streamObserver.onError(failure);
    }

    @Override
    public void onCompletion() {
        streamObserver.onCompleted();
    }
}
