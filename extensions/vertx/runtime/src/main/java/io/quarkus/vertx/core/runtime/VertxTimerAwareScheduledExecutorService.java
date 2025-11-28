package io.quarkus.vertx.core.runtime;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import io.vertx.core.Context;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;

public class VertxTimerAwareScheduledExecutorService implements ScheduledExecutorService {

    private final ScheduledExecutorService delegate;

    public VertxTimerAwareScheduledExecutorService(ScheduledExecutorService delegate) {
        this.delegate = delegate;
    }

    // ------------------ ScheduledFuture over Vert.x Future ------------------ //

    private static class VertxFutureWrapper<T> implements ScheduledFuture<T> {

        final Vertx vertx;
        final long timerId;
        final io.vertx.core.Future<T> future;
        volatile boolean cancelled;

        VertxFutureWrapper(Vertx vertx, long timerId, io.vertx.core.Future<T> future) {
            this.vertx = vertx;
            this.timerId = timerId;
            this.future = future;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int compareTo(Delayed o) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return vertx.cancelTimer(timerId);
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return future.isComplete();
        }

        @Override
        public T get() throws InterruptedException, ExecutionException {
            throw new UnsupportedOperationException();
        }

        @Override
        public T get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            throw new UnsupportedOperationException();
        }
    }

    // ------------------ ScheduledExecutorService ------------------ //

    @Override
    public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        Context context = Vertx.currentContext();
        if (context != null) {
            Promise<Void> promise = Promise.promise();
            Vertx vertx = context.owner();
            long timerId = vertx.setTimer(unit.toMillis(delay), tick -> {
                command.run();
                promise.complete();
            });
            return new VertxFutureWrapper<>(vertx, timerId, promise.future());
        }
        return delegate.schedule(command, delay, unit);
    }

    @Override
    public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
        // Not used by Mutiny
        return delegate.schedule(callable, delay, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
        Context context = Vertx.currentContext();
        if (context != null) {
            Vertx vertx = context.owner();
            long timerId = vertx.setPeriodic(unit.toMillis(initialDelay), unit.toMillis(period), tick -> command.run());
            return new VertxFutureWrapper<Void>(vertx, timerId, io.vertx.core.Future.succeededFuture());
        }
        return delegate.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
        // Not used by Mutiny
        return delegate.scheduleWithFixedDelay(command, initialDelay, delay, unit);
    }

    // ------------------ ExecutorService ------------------ //

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(task);
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(task, result);
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(task);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(tasks);
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException {
        return delegate.invokeAll(tasks, timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(tasks);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(tasks, timeout, unit);
    }

    @Override
    public void execute(Runnable command) {
        delegate.execute(command);
    }
}
