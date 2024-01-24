package io.quarkus.it.mutiny.nativejctools;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;

import io.smallrye.mutiny.helpers.queues.Queues;

@Path("/tests")
public class MyResource {

    @GET
    @Path("create-queues")
    public String createQueues() {
        ArrayList<Long> sums = new ArrayList<>();
        List.of(
                Queues.createMpscQueue(),
                Queues.createMpscArrayQueue(2048),
                Queues.createSpscArrayQueue(2048),
                Queues.createSpscUnboundedQueue(2048),
                Queues.createSpscUnboundedArrayQueue(2048),
                Queues.createSpscChunkedArrayQueue(2048)).forEach(queue -> {
                    for (int i = 0; i < 1024; i++) {
                        queue.offer(i);
                    }
                    long sum = 0L;
                    for (Integer n = (Integer) queue.poll(); n != null; n = (Integer) queue.poll()) {
                        sum = sum + n;
                    }
                    sums.add(sum);
                });
        return "Ok :: " + sums.stream()
                .map(Objects::toString)
                .collect(Collectors.joining("/"));
    }
}
