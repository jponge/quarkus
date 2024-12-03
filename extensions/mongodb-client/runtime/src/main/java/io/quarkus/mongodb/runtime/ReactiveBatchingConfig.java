package io.quarkus.mongodb.runtime;

import io.quarkus.runtime.annotations.ConfigGroup;
import io.quarkus.runtime.annotations.ConfigItem;

/**
 * TBA
 */
@ConfigGroup
public class ReactiveBatchingConfig {

    /**
     * TBA
     */
    @ConfigItem(defaultValue = "true")
    public boolean enabled;

    /**
     * TBA
     */
    @ConfigItem(defaultValue = "512")
    public int batchSize;

    /**
     * TBA
     */
    @ConfigItem(defaultValue = "100")
    public int maxDelay;
}
