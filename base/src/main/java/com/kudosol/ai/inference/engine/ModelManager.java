package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.operator.OperatorRegistry;
import com.kudosol.ai.inference.operator.PipelinePostprocessor;
import com.kudosol.ai.inference.operator.PipelinePreprocessor;
import com.kudosol.ai.inference.spi.*;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(2)
public class ModelManager implements ApplicationRunner {

    private static final Set<String> RESERVED_DIRS = Set.of("preprocessor", "postprocessor");

    private final InferenceProperties properties;
    private final OperatorRegistry operatorRegistry;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String, ModelContainer> models = new ConcurrentHashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        init();
    }

    private void init() {
        Path modelDir = Path.of(properties.getModelDir());
        if (!Files.isDirectory(modelDir)) {
            log.warn("模型目录不存在: {}", modelDir);
            return;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelDir)) {
            for (Path dir : stream) {
                if (Files.isDirectory(dir) && !isReservedDir(dir)) {
                    loadModel(dir);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("扫描模型目录失败: " + modelDir, e);
        }

        log.info("已加载 {} 个模型: {}", models.size(), models.keySet());
    }

    private void loadModel(Path dir) {
        String modelName = dir.getFileName().toString();
        try {
            Path metaFile = dir.resolve("model.yml");
            if (!Files.exists(metaFile)) {
                log.warn("模型 {} 缺少 model.yml，跳过", modelName);
                return;
            }
            ModelMeta meta;
            try (var is = Files.newInputStream(metaFile)) {
                meta = parseMeta(new Yaml().load(is));
            }

            Path onnxFile = dir.resolve("model.onnx");
            if (!Files.exists(onnxFile)) {
                log.warn("模型 {} 缺少 model.onnx，跳过", modelName);
                return;
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setInterOpNumThreads(properties.getThreadCount());
            opts.setIntraOpNumThreads(properties.getThreadCount());
            OrtSession session = env.createSession(onnxFile.toString(), opts);

            // Preprocessor: model.yml 算子管线 > SPI JAR > 自动生成管线
            Preprocessor preprocessor = resolvePreprocessor(dir, meta, modelName);

            // Postprocessor: model.yml 算子管线 > SPI JAR > 自动生成管线
            Postprocessor postprocessor = resolvePostprocessor(dir, meta, modelName);

            preprocessor.init(meta);
            postprocessor.init(meta);

            log.info("模型 [{}] 输入: {}", modelName, session.getInputNames());
            log.info("模型 [{}] 输出: {}", modelName, session.getOutputNames());
            session.getInputInfo().forEach((name, info) ->
                    log.info("  输入 [{}]: {}", name, info.getInfo()));
            session.getOutputInfo().forEach((name, info) ->
                    log.info("  输出 [{}]: {}", name, info.getInfo()));

            models.put(modelName, new ModelContainer(modelName, meta.getVersion(), session, preprocessor, postprocessor));
            log.info("模型 [{}] v{} 加载成功", modelName, meta.getVersion());
        } catch (Exception e) {
            throw new IllegalStateException("加载模型 [%s] 失败: %s".formatted(modelName, e.getMessage()), e);
        }
    }

    private Preprocessor resolvePreprocessor(Path dir, ModelMeta meta, String modelName) {
        // 1. model.yml 声明了算子管线
        if (meta.getPreprocess() != null && !meta.getPreprocess().isEmpty()) {
            log.info("模型 [{}] 使用算子管线前处理器 ({} 步)", modelName, meta.getPreprocess().size());
            return new PipelinePreprocessor(meta.getPreprocess(), operatorRegistry);
        }

        // 2. SPI JAR 自定义前处理器
        Preprocessor spi = ModelClassLoader.loadPreprocessor(dir);
        if (spi != null) {
            log.info("模型 [{}] 使用自定义前处理器: {}", modelName, spi.getClass().getName());
            return spi;
        }

        // 3. 根据 ModelMeta.inputs 自动生成管线
        List<PipelineStep> autoSteps = buildAutoPreprocessSteps(meta);
        log.info("模型 [{}] 使用自动生成管线前处理器 ({} 步)", modelName, autoSteps.size());
        return new PipelinePreprocessor(autoSteps, operatorRegistry);
    }

    private Postprocessor resolvePostprocessor(Path dir, ModelMeta meta, String modelName) {
        // 1. model.yml 声明了算子管线
        if (meta.getPostprocess() != null && !meta.getPostprocess().isEmpty()) {
            log.info("模型 [{}] 使用算子管线后处理器 ({} 步)", modelName, meta.getPostprocess().size());
            return new PipelinePostprocessor(meta.getPostprocess(), operatorRegistry);
        }

        // 2. SPI JAR 自定义后处理器
        Postprocessor spi = ModelClassLoader.loadPostprocessor(dir);
        if (spi != null) {
            log.info("模型 [{}] 使用自定义后处理器: {}", modelName, spi.getClass().getName());
            return spi;
        }

        // 3. 空管线：直接取 tensor.getValue() 返回
        log.info("模型 [{}] 使用自动生成管线后处理器 (直通)", modelName);
        return new PipelinePostprocessor(List.of(), operatorRegistry);
    }

    private List<PipelineStep> buildAutoPreprocessSteps(ModelMeta meta) {
        List<PipelineStep> steps = new ArrayList<>();

        PipelineStep parseStep = new PipelineStep();
        parseStep.setId("auto_parse");
        parseStep.setOp("parse_json");
        steps.add(parseStep);

        for (TensorMeta tensorMeta : meta.getInputs()) {
            PipelineStep toTensorStep = new PipelineStep();
            toTensorStep.setId("auto_tensor_" + tensorMeta.getName());
            toTensorStep.setOp("to_tensor");
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("field", tensorMeta.getName());
            params.put("name", tensorMeta.getName());
            params.put("type", tensorMeta.getType());
            if (tensorMeta.getShape() != null) {
                params.put("shape", tensorMeta.getShape());
            }
            toTensorStep.setParams(params);
            toTensorStep.setInputs(List.of("auto_parse"));
            steps.add(toTensorStep);
        }

        return steps;
    }

    @SuppressWarnings("unchecked")
    private ModelMeta parseMeta(Map<String, Object> raw) {
        ModelMeta meta = new ModelMeta();
        meta.setName((String) raw.getOrDefault("name", ""));
        meta.setVersion((String) raw.getOrDefault("version", "unknown"));
        meta.setDescription((String) raw.getOrDefault("description", ""));

        List<Map<String, Object>> rawInputs = (List<Map<String, Object>>) raw.getOrDefault("inputs", List.of());
        for (Map<String, Object> in : rawInputs) {
            meta.getInputs().add(parseTensorMeta(in));
        }

        List<Map<String, Object>> rawOutputs = (List<Map<String, Object>>) raw.getOrDefault("outputs", List.of());
        for (Map<String, Object> out : rawOutputs) {
            meta.getOutputs().add(parseTensorMeta(out));
        }

        List<Map<String, Object>> rawPreprocess = (List<Map<String, Object>>) raw.get("preprocess");
        if (rawPreprocess != null && !rawPreprocess.isEmpty()) {
            meta.setPreprocess(rawPreprocess.stream()
                    .map(this::parsePipelineStep)
                    .toList());
        }

        List<Map<String, Object>> rawPostprocess = (List<Map<String, Object>>) raw.get("postprocess");
        if (rawPostprocess != null && !rawPostprocess.isEmpty()) {
            meta.setPostprocess(rawPostprocess.stream()
                    .map(this::parsePipelineStep)
                    .toList());
        }

        return meta;
    }

    @SuppressWarnings("unchecked")
    private PipelineStep parsePipelineStep(Map<String, Object> raw) {
        PipelineStep step = new PipelineStep();
        step.setId((String) raw.get("id"));
        step.setOp((String) raw.get("op"));
        step.setParams((Map<String, Object>) raw.get("params"));
        step.setInputs((List<String>) raw.get("inputs"));
        return step;
    }

    @SuppressWarnings("unchecked")
    private TensorMeta parseTensorMeta(Map<String, Object> raw) {
        TensorMeta tm = new TensorMeta();
        tm.setName((String) raw.get("name"));
        tm.setType((String) raw.get("type"));

        Object shapeObj = raw.get("shape");
        if (shapeObj instanceof List<?> shapeList) {
            tm.setShape(shapeList.stream()
                    .map(v -> ((Number) v).longValue())
                    .toList());
        }

        return tm;
    }

    private boolean isReservedDir(Path dir) {
        return RESERVED_DIRS.contains(dir.getFileName().toString());
    }

    public ModelContainer getModel(String name) {
        ModelContainer container = models.get(name);
        if (container == null) {
            throw new IllegalArgumentException("模型不存在: " + name);
        }
        return container;
    }

    public Map<String, ModelContainer> getModels() {
        return Collections.unmodifiableMap(models);
    }

    @PreDestroy
    public void destroy() {
        models.values().forEach(ModelContainer::close);
        env.close();
    }
}
