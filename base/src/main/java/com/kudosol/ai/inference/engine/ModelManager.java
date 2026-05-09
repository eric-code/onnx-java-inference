package com.kudosol.ai.inference.engine;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.operator.Operator;
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

    private static final Set<String> RESERVED_DIRS = Set.of("preprocessor", "postprocessor", "operators");

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

            populateMetaFromOnnx(meta, session);

            // Preprocessor: model.yml 算子管线 > SPI JAR > 自动生成管线
            OperatorRegistry modelRegistry = resolveModelRegistry(dir, modelName);
            Preprocessor preprocessor = resolvePreprocessor(dir, meta, modelName, modelRegistry);

            // Postprocessor: model.yml 算子管线 > SPI JAR > 自动生成管线
            Postprocessor postprocessor = resolvePostprocessor(dir, meta, modelName, modelRegistry);

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

    private OperatorRegistry resolveModelRegistry(Path dir, String modelName) {
        List<Operator> customOps = ModelClassLoader.loadOperators(dir);
        if (customOps.isEmpty()) {
            return operatorRegistry;
        }
        log.info("模型 [{}] 加载 {} 个自定义算子", modelName, customOps.size());
        return operatorRegistry.withOperators(customOps);
    }

    private Preprocessor resolvePreprocessor(Path dir, ModelMeta meta, String modelName, OperatorRegistry registry) {
        // 1. model.yml 声明了算子管线
        if (meta.getPreprocess() != null && !meta.getPreprocess().isEmpty()) {
            log.info("模型 [{}] 使用算子管线前处理器 ({} 步)", modelName, meta.getPreprocess().size());
            return new PipelinePreprocessor(meta.getPreprocess(), registry);
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
        return new PipelinePreprocessor(autoSteps, registry);
    }

    private Postprocessor resolvePostprocessor(Path dir, ModelMeta meta, String modelName, OperatorRegistry registry) {
        // 1. model.yml 声明了算子管线
        if (meta.getPostprocess() != null && !meta.getPostprocess().isEmpty()) {
            log.info("模型 [{}] 使用算子管线后处理器 ({} 步)", modelName, meta.getPostprocess().size());
            return new PipelinePostprocessor(meta.getPostprocess(), registry);
        }

        // 2. SPI JAR 自定义后处理器
        Postprocessor spi = ModelClassLoader.loadPostprocessor(dir);
        if (spi != null) {
            log.info("模型 [{}] 使用自定义后处理器: {}", modelName, spi.getClass().getName());
            return spi;
        }

        // 3. 空管线：直接取 tensor.getValue() 返回
        log.info("模型 [{}] 使用自动生成管线后处理器 (直通)", modelName);
        return new PipelinePostprocessor(List.of(), registry);
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
            toTensorStep.setParams(params);
            toTensorStep.setInputs(List.of("auto_parse"));
            steps.add(toTensorStep);
        }

        return steps;
    }

    private void populateMetaFromOnnx(ModelMeta meta, OrtSession session) throws Exception {
        meta.setInputs(toTensorMetas(session.getInputInfo()));
        meta.setOutputs(toTensorMetas(session.getOutputInfo()));
    }

    private List<TensorMeta> toTensorMetas(Map<String, NodeInfo> nodeInfos) {
        List<TensorMeta> result = new ArrayList<>();
        for (Map.Entry<String, NodeInfo> entry : nodeInfos.entrySet()) {
            String name = entry.getKey();
            Object info = entry.getValue().getInfo();
            if (info instanceof TensorInfo ti) {
                String type = OnnxTypeMapper.toYmlType(ti.type);
                List<Long> shape = Arrays.stream(ti.getShape()).boxed().toList();
                result.add(new TensorMeta(name, type, shape));
            } else {
                result.add(new TensorMeta(name, null, null));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private ModelMeta parseMeta(Map<String, Object> raw) {
        if (raw.containsKey("inputs")) {
            throw new IllegalStateException("model.yml 不允许声明 inputs，该字段已自动从 ONNX 元数据推断，请删除");
        }
        if (raw.containsKey("outputs")) {
            throw new IllegalStateException("model.yml 不允许声明 outputs，该字段已自动从 ONNX 元数据推断，请删除");
        }

        ModelMeta meta = new ModelMeta();
        meta.setName((String) raw.getOrDefault("name", ""));
        meta.setVersion((String) raw.getOrDefault("version", "unknown"));
        meta.setDescription((String) raw.getOrDefault("description", ""));

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

        if ("to_tensor".equals(step.getOp()) && step.getParams() != null) {
            for (String forbidden : List.of("name", "type", "shape")) {
                if (step.getParams().containsKey(forbidden)) {
                    throw new IllegalStateException(
                            "to_tensor.params 不允许声明 " + forbidden + "，已自动从 ONNX 元数据推断");
                }
            }
        }
        return step;
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
