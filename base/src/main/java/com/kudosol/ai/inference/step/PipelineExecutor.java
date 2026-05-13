package com.kudosol.ai.inference.step;

import com.kudosol.ai.inference.exception.BadRequestException;
import com.kudosol.ai.inference.spi.PipelineStep;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

public class PipelineExecutor {

    public List<PipelineStep> normalize(List<PipelineStep> steps) {
        boolean hasExplicitId = steps.stream().anyMatch(s -> s.getId() != null && !s.getId().isBlank());
        if (!hasExplicitId) {
            List<PipelineStep> normalized = new ArrayList<>();
            for (int i = 0; i < steps.size(); i++) {
                PipelineStep original = steps.get(i);
                PipelineStep step = new PipelineStep();
                step.setId("step_" + i);
                step.setStep(original.getStep());
                step.setParams(original.getParams());
                if (i > 0) {
                    step.setInputs(List.of("step_" + (i - 1)));
                }
                normalized.add(step);
            }
            return normalized;
        }
        return steps;
    }

    public void validate(List<PipelineStep> steps, StepRegistry registry) {
        if (steps == null || steps.isEmpty()) return;

        Set<String> ids = new HashSet<>();
        for (PipelineStep step : steps) {
            if (step.getId() == null || step.getId().isBlank()) {
                throw new BadRequestException("DAG 模式下所有步骤必须有 id");
            }
            if (!ids.add(step.getId())) {
                throw new BadRequestException("重复的步骤 id: " + step.getId());
            }
            if (step.getStep() == null || step.getStep().isBlank()) {
                throw new BadRequestException("步骤 " + step.getId() + " 缺少 step");
            }
            if (!registry.contains(step.getStep())) {
                throw new BadRequestException("步骤 " + step.getId() + " 引用未知步骤: " + step.getStep());
            }
            if (step.getInputs() != null) {
                for (String inputId : step.getInputs()) {
                    if (inputId.equals(step.getId())) {
                        throw new BadRequestException("步骤 " + step.getId() + " 不能引用自身");
                    }
                }
            }
        }

        Set<String> idSet = steps.stream().map(PipelineStep::getId).collect(Collectors.toSet());
        for (PipelineStep step : steps) {
            if (step.getInputs() != null) {
                for (String inputId : step.getInputs()) {
                    if (!idSet.contains(inputId)) {
                        throw new BadRequestException("步骤 " + step.getId() + " 引用不存在的上游: " + inputId);
                    }
                }
            }
        }
    }

    public List<List<PipelineStep>> topologicalSort(List<PipelineStep> steps) {
        if (steps.isEmpty()) return List.of();

        Map<String, PipelineStep> byId = steps.stream()
                .collect(Collectors.toMap(PipelineStep::getId, Function.identity()));
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = new HashMap<>();

        for (PipelineStep step : steps) {
            inDegree.putIfAbsent(step.getId(), 0);
            adj.putIfAbsent(step.getId(), new ArrayList<>());
            if (step.getInputs() != null) {
                for (String inputId : step.getInputs()) {
                    adj.computeIfAbsent(inputId, k -> new ArrayList<>()).add(step.getId());
                    inDegree.merge(step.getId(), 1, Integer::sum);
                }
            }
        }

        List<List<PipelineStep>> layers = new ArrayList<>();
        Queue<String> queue = new LinkedList<>();
        inDegree.entrySet().stream()
                .filter(e -> e.getValue() == 0)
                .forEach(e -> queue.add(e.getKey()));

        int processed = 0;
        while (!queue.isEmpty()) {
            List<PipelineStep> layer = new ArrayList<>();
            int layerSize = queue.size();
            for (int i = 0; i < layerSize; i++) {
                String id = queue.poll();
                layer.add(byId.get(id));
                processed++;
                for (String next : adj.getOrDefault(id, List.of())) {
                    int newDeg = inDegree.merge(next, -1, Integer::sum);
                    if (newDeg == 0) queue.add(next);
                }
            }
            layers.add(layer);
        }

        if (processed != steps.size()) {
            throw new BadRequestException("管线存在循环依赖");
        }
        return layers;
    }

    public void execute(List<PipelineStep> steps, Map<String, Object> context, StepRegistry registry) {
        if (steps.isEmpty()) return;

        List<PipelineStep> normalized = normalize(steps);
        validate(normalized, registry);
        List<List<PipelineStep>> layers = topologicalSort(normalized);

        for (List<PipelineStep> layer : layers) {
            if (layer.size() == 1) {
                Map<String, Object> result = runStep(layer.get(0), context, registry);
                mergeResult(context, result);
            } else {
                List<CompletableFuture<Map<String, Object>>> futures = layer.stream()
                        .map(step -> CompletableFuture.supplyAsync(
                                () -> runStep(step, context, registry)))
                        .toList();

                try {
                    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                } catch (CompletionException e) {
                    throw new RuntimeException("管线并行执行失败: " + e.getCause().getMessage(), e.getCause());
                }

                for (CompletableFuture<Map<String, Object>> f : futures) {
                    mergeResult(context, f.join());
                }
            }
        }
    }

    private Map<String, Object> runStep(PipelineStep step, Map<String, Object> context, StepRegistry registry) {
        Step stepImpl = registry.get(step.getStep());
        Map<String, Object> params = step.getParams() != null ? step.getParams() : Map.of();
        return stepImpl.execute(context, params);
    }

    private void mergeResult(Map<String, Object> context, Map<String, Object> result) {
        for (Map.Entry<String, Object> entry : result.entrySet()) {
            if (entry.getValue() == null) {
                context.remove(entry.getKey());
            } else {
                context.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
