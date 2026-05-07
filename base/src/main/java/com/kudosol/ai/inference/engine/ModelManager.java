package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.Preprocessor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

@Slf4j
@Component
@RequiredArgsConstructor
public class ModelManager {

    private static final Set<String> RESERVED_DIRS = Set.of("preprocessor", "postprocessor");

    private final InferenceProperties properties;
    private final OrtEnvironment env = OrtEnvironment.getEnvironment();
    private final Map<String, ModelContainer> models = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
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
            Map<String, Object> meta;
            try (var is = Files.newInputStream(metaFile)) {
                meta = new Yaml().load(is);
            }
            String version = (String) meta.getOrDefault("version", "unknown");

            Path onnxFile = dir.resolve("model.onnx");
            if (!Files.exists(onnxFile)) {
                log.warn("模型 {} 缺少 model.onnx，跳过", modelName);
                return;
            }
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setInterOpNumThreads(properties.getThreadCount());
            opts.setIntraOpNumThreads(properties.getThreadCount());
            OrtSession session = env.createSession(onnxFile.toString(), opts);

            Preprocessor preprocessor = ModelClassLoader.loadPreprocessor(dir);
            Postprocessor postprocessor = ModelClassLoader.loadPostprocessor(dir);

            log.info("模型 [{}] 输入: {}", modelName, session.getInputNames());
            log.info("模型 [{}] 输出: {}", modelName, session.getOutputNames());
            session.getInputInfo().forEach((name, info) ->
                    log.info("  输入 [{}]: {}", name, info.getInfo()));
            session.getOutputInfo().forEach((name, info) ->
                    log.info("  输出 [{}]: {}", name, info.getInfo()));

            models.put(modelName, new ModelContainer(modelName, version, session, preprocessor, postprocessor));
            log.info("模型 [{}] v{} 加载成功", modelName, version);
        } catch (Exception e) {
            throw new IllegalStateException("加载模型 [%s] 失败: %s".formatted(modelName, e.getMessage()), e);
        }
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
