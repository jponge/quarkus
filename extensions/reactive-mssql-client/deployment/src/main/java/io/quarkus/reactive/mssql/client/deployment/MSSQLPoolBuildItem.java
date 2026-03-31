package io.quarkus.reactive.mssql.client.deployment;

import java.util.function.Function;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.vertx.sqlclient.Pool;

@Deprecated(since = "3.21", forRemoval = true)
public final class MSSQLPoolBuildItem extends MultiBuildItem {

    private final String dataSourceName;

    private final Function<SyntheticCreationalContext<Pool>, Pool> mssqlPool;

    public MSSQLPoolBuildItem(String dataSourceName, Function<SyntheticCreationalContext<Pool>, Pool> mssqlPool) {
        this.dataSourceName = dataSourceName;
        this.mssqlPool = mssqlPool;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public Function<SyntheticCreationalContext<Pool>, Pool> getMSSQLPool() {
        return mssqlPool;
    }

    public boolean isDefault() {
        return DataSourceUtil.isDefault(dataSourceName);
    }

}
