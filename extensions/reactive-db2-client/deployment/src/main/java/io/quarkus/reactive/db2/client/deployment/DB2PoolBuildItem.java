package io.quarkus.reactive.db2.client.deployment;

import java.util.function.Function;

import io.quarkus.arc.SyntheticCreationalContext;
import io.quarkus.builder.item.MultiBuildItem;
import io.quarkus.datasource.common.runtime.DataSourceUtil;
import io.vertx.sqlclient.Pool;

@Deprecated(since = "3.21", forRemoval = true)
public final class DB2PoolBuildItem extends MultiBuildItem {

    private final String dataSourceName;

    private final Function<SyntheticCreationalContext<Pool>, Pool> db2Pool;

    public DB2PoolBuildItem(String dataSourceName, Function<SyntheticCreationalContext<Pool>, Pool> db2Pool) {
        this.dataSourceName = dataSourceName;
        this.db2Pool = db2Pool;
    }

    public String getDataSourceName() {
        return dataSourceName;
    }

    public Function<SyntheticCreationalContext<Pool>, Pool> getDB2Pool() {
        return db2Pool;
    }

    public boolean isDefault() {
        return DataSourceUtil.isDefault(dataSourceName);
    }

}
