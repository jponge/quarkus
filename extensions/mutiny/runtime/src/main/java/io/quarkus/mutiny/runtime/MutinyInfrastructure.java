package io.quarkus.mutiny.runtime;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import org.jboss.logging.Logger;

import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.infrastructure.InfrastructureConfiguration;

/**
 * Utility methods for configuring the Mutiny {@link Infrastructure} during application startup.
 */
public class MutinyInfrastructure {

    /**
     * The prefix used by Vert.x event loop thread names.
     */
    public static final String VERTX_EVENT_LOOP_THREAD_PREFIX = "vert.x-eventloop-thread-";

    /**
     * Configure the Mutiny infrastructure at static-init time.
     * <p>
     * This sets the dropped exception handler, the thread blocking checker, the operator logger,
     * and pre-sets empty interceptor / decorator lists so that no {@link java.util.ServiceLoader}
     * classpath scanning is performed at runtime.
     * <p>
     * Passing empty lists means that any {@code UniInterceptor}, {@code MultiInterceptor}, or
     * {@code CallbackDecorator} registered via {@code META-INF/services} SPI files will be
     * ignored. This is intentional: in Quarkus, Mutiny interceptors should be registered
     * through the build-time extension mechanism, not via ServiceLoader.
     */
    public static void configureInfrastructure() {
        Logger logger = Logger.getLogger(MutinyInfrastructure.class);
        Infrastructure.configure(new InfrastructureConfiguration()
                .withUniInterceptors(List.of())
                .withMultiInterceptors(List.of())
                .withCallbackDecorators(List.of())
                .withDroppedExceptionHandler(new Consumer<Throwable>() {
                    @Override
                    public void accept(Throwable throwable) {
                        logger.error("Mutiny had to drop the following exception", throwable);
                    }
                })
                .withCanCallerThreadBeBlockedSupplier(new BooleanSupplier() {
                    @Override
                    public boolean getAsBoolean() {
                        String threadName = Thread.currentThread().getName();
                        return !threadName.startsWith(VERTX_EVENT_LOOP_THREAD_PREFIX);
                    }
                })
                .withOperatorLogger(new Infrastructure.OperatorLogger() {
                    @Override
                    public void log(String identifier, String event, Object value, Throwable failure) {
                        String log = identifier + " | ";
                        if (failure != null) {
                            log = log + event + "(" + failure.getClass() + "(" + failure.getMessage() + "))";
                        } else if (value != null) {
                            log = log + event + "(" + value + ")";
                        } else {
                            log = log + event + "()";
                        }
                        logger.info(log);
                    }
                }));
    }

    /**
     * Configure the Mutiny infrastructure to use the given executor as the default executor.
     * <p>
     * With the lazy-init Mutiny infrastructure, no default worker pool is created eagerly,
     * so there is nothing to shut down before replacing the executor.
     *
     * @param executor the executor to use as the default executor (must not be {@code null})
     */
    public static void configureMutinyInfrastructure(ScheduledExecutorService executor) {
        Infrastructure.setDefaultExecutor(executor, false);
    }
}
