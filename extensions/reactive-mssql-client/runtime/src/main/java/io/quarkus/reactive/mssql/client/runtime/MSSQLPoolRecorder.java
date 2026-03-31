package io.quarkus.reactive.mssql.client.runtime;

import static io.quarkus.credentials.CredentialsProvider.PASSWORD_PROPERTY_NAME;
import static io.quarkus.credentials.CredentialsProvider.USER_PROPERTY_NAME;
import static io.quarkus.reactive.datasource.runtime.ReactiveDataSourceUtil.qualifier;
import static io.quarkus.reactive.datasource.runtime.UnitisedTime.unitised;

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
import io.vertx.core.internal.VertxInternal;
import io.vertx.core.net.ClientSSLOptions;
import io.vertx.core.net.JksOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.core.net.PfxOptions;
import io.vertx.mssqlclient.MSSQLBuilder;
import io.vertx.mssqlclient.MSSQLConnectOptions;
import io.vertx.sqlclient.ClientBuilder;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlConnectOptions;
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
                Pool pool = initialize((VertxInternal) vertx.getValue(),
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
            @SuppressWarnings("unchecked")
            @Override
            public io.vertx.mutiny.sqlclient.Pool apply(SyntheticCreationalContext context) {
                return io.vertx.mutiny.sqlclient.Pool.newInstance(
                        (Pool) context.getInjectedReference(Pool.class, qualifier(dataSourceName)));
            }
        };
    }

    private Pool initialize(VertxInternal vertx,
            Integer eventLoopCount,
            String dataSourceName, DataSourceRuntimeConfig dataSourceRuntimeConfig,
            DataSourceReactiveRuntimeConfig dataSourceReactiveRuntimeConfig,
            DataSourceReactiveMSSQLConfig dataSourceReactiveMSSQLConfig,
            SyntheticCreationalContext<Pool> context) {
        PoolOptions poolOptions = toPoolOptions(eventLoopCount, dataSourceReactiveRuntimeConfig);
        MSSQLConnectOptions mssqlConnectOptions = toMSSQLConnectOptions(dataSourceName, dataSourceRuntimeConfig,
                dataSourceReactiveRuntimeConfig, dataSourceReactiveMSSQLConfig);
        Supplier<Future<SqlConnectOptions>> databasesSupplier = toDatabasesSupplier(List.of(mssqlConnectOptions),
                dataSourceRuntimeConfig);
        return createPool(vertx, poolOptions, mssqlConnectOptions, dataSourceName, databasesSupplier, context);
    }

    @SuppressWarnings("unchecked")
    private Supplier<Future<SqlConnectOptions>> toDatabasesSupplier(List<MSSQLConnectOptions> mssqlConnectOptionsList,
            DataSourceRuntimeConfig dataSourceRuntimeConfig) {
        Supplier<Future<SqlConnectOptions>> supplier;
        if (dataSourceRuntimeConfig.credentialsProvider().isPresent()) {
            String beanName = dataSourceRuntimeConfig.credentialsProviderName().orElse(null);
            CredentialsProvider credentialsProvider = CredentialsProviderFinder.find(beanName);
            String name = dataSourceRuntimeConfig.credentialsProvider().get();
            supplier = (Supplier) new ConnectOptionsSupplier<>(credentialsProvider, name, mssqlConnectOptionsList,
                    MSSQLConnectOptions::new);
        } else {
            supplier = (Supplier) Utils.roundRobinSupplier(mssqlConnectOptionsList);
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

        ClientSSLOptions sslOptions = new ClientSSLOptions();
        sslOptions.setTrustAll(dataSourceReactiveRuntimeConfig.trustAll());

        var pemTrust = dataSourceReactiveRuntimeConfig.trustCertificatePem();
        if (pemTrust.enabled() || (pemTrust.certs().isPresent() && !pemTrust.certs().get().isEmpty())) {
            PemTrustOptions pemTrustOptions = new PemTrustOptions();
            if (pemTrust.certs().isPresent()) {
                for (String cert : pemTrust.certs().get()) {
                    pemTrustOptions.addCertPath(cert);
                }
            }
            sslOptions.setTrustOptions(pemTrustOptions);
        }

        var jksTrust = dataSourceReactiveRuntimeConfig.trustCertificateJks();
        if (jksTrust.enabled()) {
            JksOptions jksOptions = new JksOptions();
            jksTrust.path().ifPresent(jksOptions::setPath);
            jksTrust.password().ifPresent(jksOptions::setPassword);
            sslOptions.setTrustOptions(jksOptions);
        }

        var pfxTrust = dataSourceReactiveRuntimeConfig.trustCertificatePfx();
        if (pfxTrust.enabled()) {
            PfxOptions pfxOptions = new PfxOptions();
            pfxTrust.path().ifPresent(pfxOptions::setPath);
            pfxTrust.password().ifPresent(pfxOptions::setPassword);
            sslOptions.setTrustOptions(pfxOptions);
        }

        var pemKeyCert = dataSourceReactiveRuntimeConfig.keyCertificatePem();
        if (pemKeyCert.enabled()) {
            PemKeyCertOptions pemKeyCertOptions = new PemKeyCertOptions();
            if (pemKeyCert.certs().isPresent()) {
                for (String cert : pemKeyCert.certs().get()) {
                    pemKeyCertOptions.addCertPath(cert);
                }
            }
            if (pemKeyCert.keys().isPresent()) {
                for (String key : pemKeyCert.keys().get()) {
                    pemKeyCertOptions.addKeyPath(key);
                }
            }
            sslOptions.setKeyCertOptions(pemKeyCertOptions);
        }

        var jksKeyCert = dataSourceReactiveRuntimeConfig.keyCertificateJks();
        if (jksKeyCert.enabled()) {
            JksOptions jksOptions = new JksOptions();
            jksKeyCert.path().ifPresent(jksOptions::setPath);
            jksKeyCert.password().ifPresent(jksOptions::setPassword);
            sslOptions.setKeyCertOptions(jksOptions);
        }

        var pfxKeyCert = dataSourceReactiveRuntimeConfig.keyCertificatePfx();
        if (pfxKeyCert.enabled()) {
            PfxOptions pfxOptions = new PfxOptions();
            pfxKeyCert.path().ifPresent(pfxOptions::setPath);
            pfxKeyCert.password().ifPresent(pfxOptions::setPassword);
            sslOptions.setKeyCertOptions(pfxOptions);
        }

        var algo = dataSourceReactiveRuntimeConfig.hostnameVerificationAlgorithm();
        if (!"NONE".equalsIgnoreCase(algo)) {
            sslOptions.setHostnameVerificationAlgorithm(algo);
        }

        mssqlConnectOptions.setSslOptions(sslOptions);

        dataSourceReactiveRuntimeConfig.additionalProperties().forEach(mssqlConnectOptions::addProperty);

        // Use the convention defined by Quarkus Micrometer Vert.x metrics to create metrics prefixed with mssql.
        // with the client_name as tag.
        // See io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractPrefix and
        // io.quarkus.micrometer.runtime.binder.vertx.VertxMeterBinderAdapter.extractClientName
        mssqlConnectOptions.setMetricsName("mssql|" + dataSourceName);

        return mssqlConnectOptions;
    }

    private Pool createPool(Vertx vertx, PoolOptions poolOptions, MSSQLConnectOptions mSSQLConnectOptions,
            String dataSourceName, Supplier<Future<SqlConnectOptions>> databases,
            SyntheticCreationalContext<Pool> context) {
        Instance<MSSQLPoolCreator> instance = context.getInjectedReference(POOL_CREATOR_TYPE_LITERAL,
                qualifier(dataSourceName));
        if (instance.isResolvable()) {
            MSSQLPoolCreator.Input input = new DefaultInput(vertx, poolOptions, mSSQLConnectOptions);
            return instance.get().create(input);
        }
        return MSSQLBuilder.pool()
                .with(poolOptions)
                .connectingTo(databases)
                .using(vertx)
                .build();
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

        @Override
        public ClientBuilder<Pool> clientBuilder() {
            return MSSQLBuilder.pool()
                    .with(poolOptions)
                    .connectingTo(mSSQLConnectOptions)
                    .using(vertx);
        }
    }

    public RuntimeValue<MSSQLPoolSupport> createMSSQLPoolSupport(Set<String> msSQLPoolNames) {
        return new RuntimeValue<>(new MSSQLPoolSupport(msSQLPoolNames));
    }
}
