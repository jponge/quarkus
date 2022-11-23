package io.quarkus.mutiny.deployment;

import java.util.Set;
import java.util.stream.Collectors;

import org.jboss.jandex.IndexView;
import org.jboss.logging.Logger;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;

public class LegacyReactiveStreamsApisReporter {

    private final Logger logger = Logger.getLogger(LegacyReactiveStreamsApisReporter.class);

    private static final String REACTIVESTREAMS_PUBLISHER = "org.reactivestreams.Publisher";

    private static final Set<String> ALLOWED_PREFIX_PACKAGES = Set.of(
            "io.smallrye.mutiny", "mutiny.zero");

    @BuildStep
    public void detectAndReport(CombinedIndexBuildItem combinedIndexBuildItem,
            BuildProducer<BytecodeTransformerBuildItem> bytecodeTransformerBuildItemBuildProducer) {
        IndexView index = combinedIndexBuildItem.getIndex();
        Set<String> packages = index.getKnownDirectImplementors(REACTIVESTREAMS_PUBLISHER)
                .stream()
                .map(classInfo -> classInfo.name().packagePrefix())
                .filter(this::filterAllowedPackages)
                .collect(Collectors.toSet());
        if (!packages.isEmpty()) {
            logger.warn(
                    "Implementations of the legacy Reactive Streams publisher API (org.reactivestreams.Publisher<T> instead of java.util.concurrent.Flow.Publisher<T>) were found in the following packages: "
                            + packages + ". See <URL TO BE ADDED> for technical details.");
        }
    }

    private boolean filterAllowedPackages(String pkg) {
        for (String prefix : ALLOWED_PREFIX_PACKAGES) {
            if (pkg.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }
}
