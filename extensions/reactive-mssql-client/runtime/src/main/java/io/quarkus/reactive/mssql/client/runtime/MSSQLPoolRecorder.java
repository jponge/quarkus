package io.quarkus.reactive.mssql.client.runtime;

import static io.quarkus.credentials.CredentialsProvider.PASSWORD_PROPERTY_NAME;
import static io.quarkus.credentials.CredentialsProvider.USER_PROPERTY_NAME;
import static io.quarkus.reactive.datasource.runtime.ReactiveDataSourceUtil.qualifier;
import static io.quarkus.reactive.datasource.runtime.UnitisedTime.unitised;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configureJksTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePemTrustOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxKeyCertOptions;
import static io.quarkus.vertx.core.runtime.SSLConfigHelper.configurePfxTrustOptions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

import org.jboss.logging.Logger;

import io.quarkus.arc.ActiveResult;
import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.credentials.CredentialsProvider;
import io.quarkus.credentials.runtime.CredentialsProviderFinder;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.quarkus.datasource.runtime.DataSourceRuntimeConfig;
import io.quarkus.datasource.runtime.DataSourcesRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.ConnectOptionsSupplier;
import io.quarkus.reactive.datasource.runtime.DataSourceReactiveRuntimeConfig;
import io.quarkus.reactive.datasource.runtime.DataSourcesReactiveRuntimeConfig;
import io.quarkus.reactive.mssql.client.MSSQLPoolCreator;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.mssqlclient.MSSQLConnectOptions;
import io.vertx.mssqlclient.spi.MSSQLDriver;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.impl.Utils;

@Recorder
public class MSSQLPoolRecorder {

    private static final TypeLiteral<Instance<MSSQLPoolCreator>> POOL_CREATOR_TYPE_LITERAL = new TypeLiteral<>() {
    };

    private static final Logger log = Logger.getLogger(MSSQLPoolRecorder.class);

    private final RuntimeValue<DataSourcesRuntimeConfig> runtimeConfig;
    private final RuntimeValue<DataSourcesReactiveRuntimeConfig> reactiveRuntimeConfig;
    private final RuntimeValue<DataSourcesReactiveMSSQLConfig> reactiveMSSQLRuntimeConfig;

    public MSSQLPoolRecorder(
            final RuntimeValue<DataSourcesRuntimeConfig> runtimeConfig,
            final RuntimeValue<DataSourcesReactiveRuntimeConfig> reactiveRuntimeConfig,
            final RuntimeValue<DataSourcesReactiveMSSQLConfig> reactiveMSSQLRuntimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.reactiveRuntimeConfig = reactiveRuntimeConfig;
        this.reactiveMSSQLRuntimeConfig = reactiveMSSQLRuntimeConfig;
    }

    public Supplier<ActiveResult> poolCheckActiveSupplier(String dataSourceName) {
        return new Supplier<>() {
            @Override
            public ActiveResult get() {
                Optional<Boolean> active = runtimeConfig.getValue().dataSources().get(dataSourceName).active();
                if (active.isPresent() && !active.get()) {
                    return ActiveResult.inactive(DataSourceUtil.dataSourceInactiveReasonDeactivated(dataSourceName));
                }
                if (reactiveRuntimeConfig.getValue().dataSources().get(dataSourceName).reactive().url().isEmpty()) {
                    return ActiveResult.inactive(DataSourceUtil.dataSourceInactiveReasonUrlMissing(dataSourceName,
                            "reactive.url"));
                }
                return ActiveResult.active();
            }
        };
    }

    public Function<SyntheticCreationalContext<Pool>, Pool> configureMSSQLPool(RuntimeValue<Vertx> vertx,
            Supplier<Integer> eventLoopCount, String dataSourceName, ShutdownContext shutdown) {
        return new Function<>() {
            @Override
            public Pool apply(SyntheticCreationalContext<Pool> context) {
                Pool pool = initialize(vertx.getValue(),
                        eventLoopCount.get(),
                        dataSourceName,
                        runtimeConfig.getValue().dataSources().get(dataSourceName),
                        reactiveRuntimeConfig.getValue().dataSources().get(dataSourceName).reactive(),
                        reactiveMSSQLRuntimeConfig.getValue().dataSources().get(dataSourceName).reactive().mssql(),
                        context);

                shutdown.addShutdownTask(pool::close);
                return pool;
            }
        };
    }

    public Function<SyntheticCreationalContext<io.vertx.mutiny.sqlclient.Pool>, io.vertx.mutiny.sqlclient.Pool> mutinyMSSQLPool(
            String dataSourceName) {
        return new Function<>() {
            @Override
            @SuppressWarnings("unchecked")
            public io.vertx.mutiny.sqlclient.Pool apply(SyntheticCreationalContext context) {
                return io.vertx.mutiny.sqlclient.Pool.newInstance(
                        (Pool) context.getInjectedReference(Pool.class, qualifier(dataSourceName)));
            }
        };
    }

    private Pool initialize(Vertx vertx,
            Integer eventLoopCount,
            String dataSourceName, DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig,
            SyntheticCreationalContext<Pool> context) {
        PoolOptions poolOptions = toPoolOptions(eventLoopCount, dataSourceReactiveRuntimeConfig);
        MSSQLConnectOptions mssqlConnectOptions = toMSSQLConnectOptions(dataSourceName, dataSourceRuntimeConfig,
                dataSourceReactiveRuntimeConfig, dataSourceReactiveMSSQLConfig);
        Supplier<Future<MSSQLConnectOptions>> databasesSupplier = toDatabasesSupplier(List.of(mssqlConnectOptions),
                dataSourceRuntimeConfig);
        NetClientOptions netClientOptions = toNetClientOptions(dataSourceReactiveRuntimeConfig);
        return createPool(vertx, poolOptions, mssqlConnectOptions, dataSourceName, databasesSupplier, netClientOptions,
                context);
    }

    private Supplier<Future<MSSQLConnectOptions>> toDatabasesSupplier(List<MSSQLConnectOptions> mssqlConnectOptionsList,
            DataSourceRuntimeConfig dataSourceRuntimeConfig) {
        Supplier<Future<MSSQLConnectOptions>> supplier;
        if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider().get();
            supplier = new ConnectOptionsSupplier<>(credentialsProvider, name, mssqlConnectOptionsList,
                    MSSQLConnectOptions::new);
        } else {
            supplier = Utils.roundRobinSupplier(mssqlConnectOptionsList);
        }
        return supplier;
    }

    private PoolOptions toPoolOptions(Integer eventLoopCount, DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig) {
        PoolOptions poolOptions;
        poolOptions = new PoolOptions();

        poolOptions.setMaxSize(dataSourceReactiveRuntimeConfig.maxSize());

        if (dataSourceReactiveRuntimeConfig.idleTimeout().isPresent()) {
            var idleTimeout = unitised(dataSourceReactiveRuntimeConfig.idleTimeout().get());
            poolOptions.setIdleTimeout(idleTimeout.value).setIdleTimeoutUnit(idleTimeout.unit);
        }

        if (dataSourceReactiveRuntimeConfig.maxLifetime().isPresent()) {
            var maxLifetime = unitised(dataSourceReactiveRuntimeConfig.maxLifetime().get());
            poolOptions.setMaxLifetime(maxLifetime.value).setMaxLifetimeUnit(maxLifetime.unit);
        }

        if (dataSourceReactiveRuntimeConfig.shared()) {
            poolOptions.setShared(true);
            if (dataSourceReactiveRuntimeConfig.name().isPresent()) {
                poolOptions.setName(dataSourceReactiveRuntimeConfig.name().get());
            }
        }

        if (dataSourceReactiveRuntimeConfig.eventLoopSize().isPresent()) {
            poolOptions.setEventLoopSize(Math.max(0, dataSourceReactiveRuntimeConfig.eventLoopSize().getAsInt()));
        } else if (eventLoopCount != null) {
            poolOptions.setEventLoopSize(Math.max(0, eventLoopCount));
        }

        return poolOptions;
    }

    private MSSQLConnectOptions toMSSQLConnectOptions(String dataSourceName, DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig) {
        MSSQLConnectOptions mssqlConnectOptions;
        if (dataSourceReactiveRuntimeConfig.url().isPresent()) {
            List<String> urls = dataSourceReactiveRuntimeConfig.url().get();
            if (urls.size() > 1) {
                log.warn("The Reactive MSSQL client does not support multiple URLs. The first one will be used, and " +
                        "others will be ignored.");
            }
            String url = urls.get(0);
            // clean up the URL to make migrations easier
            if (url.startsWith("vertx-reactive:sqlserver://")) {
                url = url.substring("vertx-reactive:".length());
            }
            mssqlConnectOptions = MSSQLConnectOptions.fromUri(url);
        } else {
            mssqlConnectOptions = new MSSQLConnectOptions();
        }

        if (dataSourceReactiveMSSQLConfig.packetSize().isPresent()) {
            mssqlConnectOptions.setPacketSize(dataSourceReactiveMSSQLConfig.packetSize().getAsInt());
        }

        if (dataSourceRuntimeConfig.username().isPresent()) {
            mssqlConnectOptions.setUser(dataSourceRuntimeConfig.username().get());
        }

        if (dataSourceRuntimeConfig.password().isPresent()) {
            mssqlConnectOptions.setPassword(dataSourceRuntimeConfig.password().get());
        }

        // credentials provider
        if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider().get();
            Map<String, String> credentials = credentialsProvider.getCredentialsAsync(name).await().indefinitely();
            String user = credentials.get(USER_PROPERTY_NAME);
            String password = credentials.get(PASSWORD_PROPERTY_NAME);
            if (user != null) {
                mssqlConnectOptions.setUser(user);
            }
            if (password != null) {
                mssqlConnectOptions.setPassword(password);
            }
        }

        mssqlConnectOptions.setReconnectAttempts(dataSourceReactiveRuntimeConfig.reconnectAttempts());

        mssqlConnectOptions.setReconnectInterval(dataSourceReactiveRuntimeConfig.reconnectInterval().toMillis());

        mssqlConnectOptions.setSsl(dataSourceReactiveMSSQLConfig.ssl());

        dataSourceReactiveRuntimeConfig.additionalProperties().forEach(mssqlConnectOptions::addProperty);

        // Use the convention defined by Quarkus Micrometer Vert.x metrics to create metrics prefixed with mssql.
        // with the client_name as tag.
        // See io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractPrefix and
        // io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractClientName
        mssqlConnectOptions.setMetricsName("mssql|" + dataSourceName);

        return mssqlConnectOptions;
    }

    private NetClientOptions toNetClientOptions(DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig) {
        NetClientOptions netClientOptions = new NetClientOptions();

        netClientOptions.setTrustAll(dataSourceReactiveRuntimeConfig.trustAll());

        configurePemTrustOptions(netClientOptions, dataSourceReactiveRuntimeConfig.trustCertificatePem());
        configureJksTrustOptions(netClientOptions, dataSourceReactiveRuntimeConfig.trustCertificateJks());
        configurePfxTrustOptions(netClientOptions, dataSourceReactiveRuntimeConfig.trustCertificatePfx());

        configurePemKeyCertOptions(netClientOptions, dataSourceReactiveRuntimeConfig.keyCertificatePem());
        configureJksKeyCertOptions(netClientOptions, dataSourceReactiveRuntimeConfig.keyCertificateJks());
        configurePfxKeyCertOptions(netClientOptions, dataSourceReactiveRuntimeConfig.keyCertificatePfx());

        var algo = dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm();
        if ("NONE".equalsIgnoreCase(algo)) {
            netClientOptions.setHostnameVerificationAlgorithm("");
        } else {
            netClientOptions.setHostnameVerificationAlgorithm(algo);
        }

        return netClientOptions;
    }

    private Pool createPool(Vertx vertx, PoolOptions poolOptions, MSSQLConnectOptions mSSQLConnectOptions,
            String dataSourceName, Supplier<Future<MSSQLConnectOptions>> databases,
            NetClientOptions netClientOptions, SyntheticCreationalContext<Pool> context) {
        Instance<MSSQLPoolCreator> instance = context.getInjectedReference(POOL_CREATOR_TYPE_LITERAL,
                qualifier(dataSourceName));
        if (instance.isResolvable()) {
            MSSQLPoolCreator.Input input = new DefaultInput(vertx, poolOptions, mSSQLConnectOptions);
            return instance.get().create(input);
        }
        return MSSQLDriver.INSTANCE.createPool(vertx, databases, poolOptions, netClientOptions, null);
    }

    private static class DefaultInput implements MSSQLPoolCreator.Input {
        private final Vertx vertx;
        private final PoolOptions poolOptions;
        private final MSSQLConnectOptions mSSQLConnectOptions;

        public DefaultInput(Vertx vertx, PoolOptions poolOptions, MSSQLConnectOptions mSSQLConnectOptions) {
            this.vertx = vertx;
            this.poolOptions = poolOptions;
            this.mSSQLConnectOptions = mSSQLConnectOptions;
        }

        @Override
        public Vertx vertx() {
            return vertx;
        }

        @Override
        public PoolOptions poolOptions() {
            return poolOptions;
        }

        @Override
        public MSSQLConnectOptions msSQLConnectOptions() {
            return mSSQLConnectOptions;
        }
    }

    public RuntimeValue<MSSQLPoolSupport> createMSSQLPoolSupport(Set<String> msSQLPoolNames) {
        return new RuntimeValue<>(new MSSQLPoolSupport(msSQLPoolNames));
    }
}
