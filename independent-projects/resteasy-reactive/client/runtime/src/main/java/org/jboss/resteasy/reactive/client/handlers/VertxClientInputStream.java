package org.jboss.resteasy.reactive.client.handlers;

import java.io.IOException;
import java.io.InputStream;

import org.jboss.resteasy.reactive.common.core.BlockingNotAllowedException;

import io.netty.buffer.ByteBuf;
import io.quarkus.vertx.utils.VertxBlockingInput;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;

public class VertxClientInputStream extends InputStream {

    private final VertxBlockingInput exchange;
    private final HttpClientResponse response;

    private boolean closed;
    private boolean finished;
    private ByteBuf pooled;

    public VertxClientInputStream(HttpClientResponse response, long timeout) {
        this.response = response;
        this.exchange = new VertxBlockingInput(
                response,
                timeout,
                () -> response.netSocket().close(),
                () -> new BlockingNotAllowedException("Attempting a blocking read on io thread"));
    }

    @Override
    public int read() throws IOException {
        byte[] b = new byte[1];
        int read = read(b);
        if (read == -1) {
            return -1;
        }
        return b[0] & 0xff;
    }

    @Override
    public int read(final byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (len == 0) {
            return 0;
        }
        readIntoBuffer();
        if (finished) {
            return -1;
        }
        ByteBuf buffer = pooled;
        int copied = Math.min(len, buffer.readableBytes());
        buffer.readBytes(b, off, copied);
        if (!buffer.isReadable()) {
            pooled.release();
            pooled = null;
        }
        return copied;
    }

    private void readIntoBuffer() throws IOException {
        if (pooled == null && !finished) {
            pooled = exchange.readBlocking();
            if (pooled == null) {
                finished = true;
                pooled = null;
            }
        }
    }

    @Override
    public int available() throws IOException {
        if (closed) {
            throw new IOException("Stream is closed");
        }
        if (finished) {
            return 0;
        }

        int buffered = exchange.readBytesAvailable();
        if (buffered > 0) {
            return buffered;
        }

        String length = response.getHeader(HttpHeaders.CONTENT_LENGTH);
        if (length == null) {
            return 0;
        }
        try {
            return Integer.parseInt(length);
        } catch (NumberFormatException e) {
            try {
                Long.parseLong(length);
            } catch (NumberFormatException ne) {
                return 0;
            }
            return Integer.MAX_VALUE;
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!finished) {
                exchange.discard();
            }
        } finally {
            if (pooled != null) {
                pooled.release();
                pooled = null;
            }
            finished = true;
        }
    }
}
