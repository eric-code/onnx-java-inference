package com.kudosol.ai.inference.health;

import com.kudosol.ai.inference.engine.ModelContainer;
import com.kudosol.ai.inference.engine.ModelManager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ModelHealthIndicator implements HealthIndicator {

    private final ModelManager modelManager;

    @Override
    public Health health() {
        Map<String, ModelContainer> models = modelManager.getModels();
        int count = models.size();

        Health.Builder builder = count > 0 ? Health.up() : Health.down();

        return builder
                .withDetail("ready", count > 0)
                .withDetail("modelCount", count)
                .withDetail("models", List.copyOf(models.keySet()))
                .build();
    }
}
