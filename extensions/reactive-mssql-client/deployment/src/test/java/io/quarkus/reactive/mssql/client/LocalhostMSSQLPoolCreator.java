package io.quarkus.reactive.mssql.client;

import jakarta.inject.Singleton;

import io.vertx.sqlclient.Pool;

@Singleton
public class LocalhostMSSQLPoolCreator implements MSSQLPoolCreator {

    @Override
    public Pool create(Input input) {
        return input.clientBuilder()
                .connectingTo(input.msSQLConnectOptions().setHost("localhost").setPort(1435))
                .build();
    }
}
