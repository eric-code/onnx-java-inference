package com.kudosol.ai.inference.operator;

import com.kudosol.ai.inference.operator.builtin.*;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OperatorRegistry {

    private final Map<String, Operator> operators = new ConcurrentHashMap<>();

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

        ServiceLoader.load(Operator.class).forEach(op -> {
            log.info("加载 SPI 自定义算子: {} ({})", op.name(), op.getClass().getName());
            register(op);
        });

        log.info("已注册 {} 个算子: {}", operators.size(), operators.keySet());
    }

    public void register(Operator op) {
        Operator existing = operators.put(op.name(), op);
        if (existing != null) {
            log.warn("算子 {} 被覆盖: {} -> {}", op.name(),
                    existing.getClass().getName(), op.getClass().getName());
        }
    }

    public Operator get(String name) {
        Operator op = operators.get(name);
        if (op == null) throw new IllegalArgumentException("未知算子: " + name);
        return op;
    }

    public boolean contains(String name) {
        return operators.containsKey(name);
    }
}
