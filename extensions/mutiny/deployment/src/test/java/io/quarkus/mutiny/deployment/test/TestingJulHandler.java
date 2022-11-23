package io.quarkus.mutiny.deployment.test;

import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

class TestingJulHandler extends Handler {

    final ArrayList<LogRecord> logRecords = new ArrayList<>();

    public ArrayList<LogRecord> getLogRecords() {
        return logRecords;
    }

    @Override
    public void publish(LogRecord record) {
        logRecords.add(record);
    }

    @Override
    public void flush() {
        // Do nothing
    }

    @Override
    public void close() throws SecurityException {
        // Do nothing
    }
}
