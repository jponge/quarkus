package io.quarkus.mutiny.deployment.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.logging.Level;

import javax.inject.Inject;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.mutiny.deployment.LegacyReactiveStreamsApisReporter;
import io.quarkus.test.QuarkusUnitTest;

public class LegacyReactiveStreamsApisReporterTest {

    @RegisterExtension
    static final QuarkusUnitTest config = new QuarkusUnitTest()
            .withApplicationRoot((jar) -> jar
                    .addClasses(TestingJulHandler.class, BeanUsingMutiny.class, LegacyPublisherImplementation.class))
            .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
            .assertLogRecords(records -> assertThat(records)
                    .filteredOn(record -> Level.WARNING.equals(record.getLevel()))
                    .filteredOn(record -> record.getLoggerName().contains(LegacyReactiveStreamsApisReporter.class.getName()))
                    .hasSize(1)
                    .first()
                    .satisfies(record -> assertThat(record.getMessage()).contains("[io.quarkus.mutiny.deployment.test]")));

    @Inject
    BeanUsingMutiny bean;

    @Test
    public void checkThatOurImplementationIsBeingReported() {
        // Just a smoke test, the actual testing work is done above
        assertThat(bean.greeting().await().atMost(Duration.ofSeconds(30)))
                .isEqualTo("hello");
    }
}
