package com.kudosol.ai.inference.step;

import com.kudosol.ai.inference.step.builtin.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class StepRegistry {

    private final Map<String, Step> steps = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        register(new ParseJson());
        register(new ExtractField());
        register(new Normalize());
        register(new Cast());
        register(new ToTensor());
        register(new ArgMax());
        register(new Softmax());
        register(new TopK());
        register(new LabelMap());
        register(new Threshold());
        register(new Round());

        ServiceLoader.load(Step.class).forEach(op -> {
            log.info("加载 SPI 自定义步骤: {} ({})", op.name(), op.getClass().getName());
            register(op);
        });

        log.info("已注册 {} 个步骤: {}", steps.size(), steps.keySet());
    }

    public void register(Step op) {
        Step existing = steps.put(op.name(), op);
        if (existing != null) {
            log.warn("步骤 {} 被覆盖: {} -> {}", op.name(),
                    existing.getClass().getName(), op.getClass().getName());
        }
    }

    public Step get(String name) {
        Step op = steps.get(name);
        if (op == null) throw new IllegalArgumentException("未知步骤: " + name);
        return op;
    }

    public boolean contains(String name) {
        return steps.containsKey(name);
    }

    /**
     * 创建包含自定义步骤的派生 Registry，自定义步骤可覆盖同名内置步骤。
     * 原 Registry 不受影响。
     */
    public StepRegistry withSteps(Iterable<Step> customSteps) {
        StepRegistry derived = new StepRegistry();
        derived.steps.putAll(this.steps);
        for (Step op : customSteps) {
            log.info("加载模型自定义步骤: {} ({})", op.name(), op.getClass().getName());
            derived.register(op);
        }
        return derived;
    }
}
