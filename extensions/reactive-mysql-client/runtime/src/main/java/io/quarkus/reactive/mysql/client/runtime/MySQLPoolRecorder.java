package io.quarkus.reactive.mysql.client.runtime;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;

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
import io.quarkus.reactive.mysql.client.MySQLPoolCreator;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.ShutdownContext;
import io.quarkus.runtime.annotations.Recorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.mysqlclient.MySQLConnectOptions;
import io.vertx.mysqlclient.SslMode;
import io.vertx.mysqlclient.spi.MySQLDriver;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.impl.Utils;

@Recorder
public class MySQLPoolRecorder {

    private static final boolean SUPPORTS_CACHE_PREPARED_STATEMENTS = true;

    private static final TypeLiteral<Instance<MySQLPoolCreator>> POOL_CREATOR_TYPE_LITERAL = new TypeLiteral<>() {
    };

    private final RuntimeValue<DataSourcesRuntimeConfig> runtimeConfig;
    private final RuntimeValue<DataSourcesReactiveRuntimeConfig> reactiveRuntimeConfig;
    private final RuntimeValue<DataSourcesReactiveMySQLConfig> reactiveMySQLRuntimeConfig;

    public MySQLPoolRecorder(
            final RuntimeValue<DataSourcesRuntimeConfig> runtimeConfig,
            final RuntimeValue<DataSourcesReactiveRuntimeConfig> reactiveRuntimeConfig,
            final RuntimeValue<DataSourcesReactiveMySQLConfig> reactiveMySQLRuntimeConfig) {
        this.runtimeConfig = runtimeConfig;
        this.reactiveRuntimeConfig = reactiveRuntimeConfig;
        this.reactiveMySQLRuntimeConfig = reactiveMySQLRuntimeConfig;
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

    public Function<SyntheticCreationalContext<Pool>, Pool> configureMySQLPool(RuntimeValue<Vertx> vertx,
            Supplier<Integer> eventLoopCount, String dataSourceName, ShutdownContext shutdown) {
        return new Function<>() {
            @Override
            public Pool apply(SyntheticCreationalContext<Pool> context) {
                Pool mysqlPool = initialize(vertx.getValue(),
                        eventLoopCount.get(),
                        dataSourceName,
                        runtimeConfig.getValue().dataSources().get(dataSourceName),
                        reactiveRuntimeConfig.getValue().dataSources().get(dataSourceName).reactive(),
                        reactiveMySQLRuntimeConfig.getValue().dataSources().get(dataSourceName).reactive().mysql(),
                        context);

                shutdown.addShutdownTask(mysqlPool::close);
                return mysqlPool;
            }
        };
    }

    public Function<SyntheticCreationalContext<io.vertx.mutiny.sqlclient.Pool>, io.vertx.mutiny.sqlclient.Pool> mutinyMySQLPool(
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
            String dataSourceName,
            DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig,
            SyntheticCreationalContext<Pool> context) {
        PoolOptions poolOptions = toPoolOptions(eventLoopCount, dataSourceReactiveRuntimeConfig,
                dataSourceReactiveMySQLConfig);
        List<MySQLConnectOptions> mySQLConnectOptions = toMySQLConnectOptions(dataSourceName, dataSourceRuntimeConfig,
                dataSourceReactiveRuntimeConfig, dataSourceReactiveMySQLConfig);
        Supplier<Future<MySQLConnectOptions>> databasesSupplier = toDatabasesSupplier(mySQLConnectOptions,
                dataSourceRuntimeConfig);
        NetClientOptions netClientOptions = toNetClientOptions(dataSourceReactiveRuntimeConfig);
        return createPool(vertx, poolOptions, mySQLConnectOptions, dataSourceName, databasesSupplier, netClientOptions,
                context);
    }

    private Supplier<Future<MySQLConnectOptions>> toDatabasesSupplier(List<MySQLConnectOptions> mySQLConnectOptions,
            DataSourceRuntimeConfig dataSourceRuntimeConfig) {
        Supplier<Future<MySQLConnectOptions>> supplier;
        if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider().get();
            supplier = new ConnectOptionsSupplier<>(credentialsProvider, name, mySQLConnectOptions,
                    MySQLConnectOptions::new);
        } else {
            supplier = Utils.roundRobinSupplier(mySQLConnectOptions);
        }
        return supplier;
    }

    private PoolOptions toPoolOptions(Integer eventLoopCount,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig) {
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

        if (dataSourceReactiveMySQLConfig.connectionTimeout().isPresent()) {
            poolOptions.setConnectionTimeout(dataSourceReactiveMySQLConfig.connectionTimeout().getAsInt());
            poolOptions.setConnectionTimeoutUnit(TimeUnit.SECONDS);
        }

        return poolOptions;
    }

    private List<MySQLConnectOptions> toMySQLConnectOptions(String dataSourceName,
            DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMySQLConfig dataSourceReactiveMySQLConfig) {
        List<MySQLConnectOptions> mysqlConnectOptionsList = new ArrayList<>();
        if (dataSourceReactiveRuntimeConfig.url().isPresent()) {
            List<String> urls = dataSourceReactiveRuntimeConfig.url().get();
            urls.forEach(url -> {
                // clean up the URL to make migrations easier
                if (url.startsWith("vertx-reactive:mysql://")) {
                    url = url.substring("vertx-reactive:".length());
                }
                mysqlConnectOptionsList.add(MySQLConnectOptions.fromUri(url));
            });
        } else {
            mysqlConnectOptionsList.add(new MySQLConnectOptions());
        }

        mysqlConnectOptionsList.forEach(mysqlConnectOptions -> {
            dataSourceRuntimeConfig.username().ifPresent(mysqlConnectOptions::setUser);

            dataSourceRuntimeConfig.password().ifPresent(mysqlConnectOptions::setPassword);

            // credentials provider
            if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
                String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
                CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
                String name = dataSourceRuntimeConfig.credentialsProvider().get();
                Map<String, String> credentials = credentialsProvider.getCredentialsAsync(name).await().indefinitely();
                String user = credentials.get(USER_PROPERTY_NAME);
                String password = credentials.get(PASSWORD_PROPERTY_NAME);
                if (user != null) {
                    mysqlConnectOptions.setUser(user);
                }
                if (password != null) {
                    mysqlConnectOptions.setPassword(password);
                }
            }

            mysqlConnectOptions
                    .setCachePreparedStatements(dataSourceReactiveRuntimeConfig.cachePreparedStatements()
                            .orElse(SUPPORTS_CACHE_PREPARED_STATEMENTS));

            dataSourceReactiveMySQLConfig.charset().ifPresent(mysqlConnectOptions::setCharset);
            dataSourceReactiveMySQLConfig.collation().ifPresent(mysqlConnectOptions::setCollation);

            if (dataSourceReactiveMySQLConfig.pipeliningLimit().isPresent()) {
                mysqlConnectOptions.setPipeliningLimit(dataSourceReactiveMySQLConfig.pipeliningLimit().getAsInt());
            }

            dataSourceReactiveMySQLConfig.useAffectedRows().ifPresent(mysqlConnectOptions::setUseAffectedRows);

            if (dataSourceReactiveMySQLConfig.sslMode().isPresent()) {
                final SslMode sslMode = dataSourceReactiveMySQLConfig.sslMode().get();
                mysqlConnectOptions.setSslMode(sslMode);

                // If sslMode is verify-identity, we also need a hostname verification algorithm
                var algo = dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm();
                if ("NONE".equalsIgnoreCase(algo) && sslMode == SslMode.VERIFY_IDENTITY) {
                    throw new IllegalArgumentException(
                            "quarkus.datasource.reactive.hostname-verification-algorithm must be specified under verify-identity sslmode");
                }
            }

            mysqlConnectOptions.setReconnectAttempts(dataSourceReactiveRuntimeConfig.reconnectAttempts());

            mysqlConnectOptions.setReconnectInterval(dataSourceReactiveRuntimeConfig.reconnectInterval().toMillis());

            dataSourceReactiveMySQLConfig.authenticationPlugin().ifPresent(mysqlConnectOptions::setAuthenticationPlugin);

            dataSourceReactiveRuntimeConfig.additionalProperties().forEach(mysqlConnectOptions::addProperty);

            // Use the convention defined by Quarkus Micrometer Vert.x metrics to create metrics prefixed with mysql.
            // and the client_name as tag.
            // See io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractPrefix and
            // io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractClientName
            mysqlConnectOptions.setMetricsName("mysql|" + dataSourceName);
        });

        return mysqlConnectOptionsList;
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

    private Pool createPool(Vertx vertx, PoolOptions poolOptions, List<MySQLConnectOptions> mySQLConnectOptionsList,
            String dataSourceName, Supplier<Future<MySQLConnectOptions>> databases,
            NetClientOptions netClientOptions,
            SyntheticCreationalContext<Pool> context) {
        Instance<MySQLPoolCreator> instance = context.getInjectedReference(POOL_CREATOR_TYPE_LITERAL,
                qualifier(dataSourceName));
        if (instance.isResolvable()) {
            MySQLPoolCreator.Input input = new DefaultInput(vertx, poolOptions, mySQLConnectOptionsList);
            return instance.get().create(input);
        }
        return MySQLDriver.INSTANCE.createPool(vertx, databases, poolOptions, netClientOptions, null);
    }

    private static class DefaultInput implements MySQLPoolCreator.Input {
        private final Vertx vertx;
        private final PoolOptions poolOptions;
        private final List<MySQLConnectOptions> mySQLConnectOptionsList;

        public DefaultInput(Vertx vertx, PoolOptions poolOptions, List<MySQLConnectOptions> mySQLConnectOptionsList) {
            this.vertx = vertx;
            this.poolOptions = poolOptions;
            this.mySQLConnectOptionsList = mySQLConnectOptionsList;
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
        public List<MySQLConnectOptions> mySQLConnectOptionsList() {
            return mySQLConnectOptionsList;
        }
    }

    public RuntimeValue<MySQLPoolSupport> createMySQLPoolSupport(Set<String> mySQLPoolNames) {
        return new RuntimeValue<>(new MySQLPoolSupport(mySQLPoolNames));
    }
}
