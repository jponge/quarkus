package io.quarkus.reactive.pg.client.deployment;

import java.util.function.Function;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.vertx.sqlclient.Pool;

@Deprecated(since = "3.21", forRemoval = true)
public final class PgPoolBuildItem extends MultiBuildItem {

    private final String dataSourceName;

    private final Function<SyntheticCreationalContext<Pool>, Pool> pgPool;

    public PgPoolBuildItem(String dataSourceName, Function<SyntheticCreationalContext<Pool>, Pool> pgPool) {
        this.dataSourceName = dataSourceName;
        this.pgPool = pgPool;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public Function<SyntheticCreationalContext<Pool>, Pool> getPgPool() {
        return pgPool;
    }

    public boolean isDefault() {
        return DataSourceUtil.isDefault(dataSourceName);
    }
}
