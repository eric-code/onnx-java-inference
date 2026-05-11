package com.kudosol.ai.inference.engine;

import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.Preprocessor;
import com.kudosol.ai.inference.step.Step;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModelClassLoader {

    private static final Logger log = LoggerFactory.getLogger(ModelClassLoader.class);

    public static Preprocessor loadPreprocessor(Path modelDir) {
        return loadSpi(modelDir, "preprocessor", Preprocessor.class);
    }

    public static Postprocessor loadPostprocessor(Path modelDir) {
        return loadSpi(modelDir, "postprocessor", Postprocessor.class);
    }

    public static List<Step> loadSteps(Path modelDir) {
        return loadAllSpi(modelDir, "steps", Step.class);
    }

    private static <T> T loadSpi(Path modelDir, String subDir, Class<T> spiType) {
        Path spiDir = modelDir.resolve(subDir);
        if (!Files.isDirectory(spiDir)) {
            log.debug("模型目录 {} 下无 {} 子目录，将使用默认处理器", modelDir.getFileName(), subDir);
            return null;
        }

        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(spiDir, "*.jar")) {
            for (Path jar : stream) {
                jars.add(jar.toUri().toURL());
            }
        } catch (Exception e) {
            log.warn("扫描 {} 目录失败: {}，将使用默认处理器", spiDir, e.getMessage());
            return null;
        }

        if (jars.isEmpty()) {
            log.debug("{} 目录下无 jar 文件，将使用默认处理器", subDir);
            return null;
        }

        URLClassLoader classLoader = new URLClassLoader(jars.toArray(new URL[0]),
                ModelClassLoader.class.getClassLoader());

        java.util.ServiceLoader<T> loader = java.util.ServiceLoader.load(spiType, classLoader);
        T impl = loader.findFirst().orElse(null);
        if (impl == null) {
            log.warn("在 {} 的 jar 中未找到 {} 的 SPI 实现，将使用默认处理器", spiDir, spiType.getName());
            return null;
        }
        return impl;
    }

    private static <T> List<T> loadAllSpi(Path modelDir, String subDir, Class<T> spiType) {
        Path spiDir = modelDir.resolve(subDir);
        if (!Files.isDirectory(spiDir)) {
            return List.of();
        }

        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(spiDir, "*.jar")) {
            for (Path jar : stream) {
                jars.add(jar.toUri().toURL());
            }
        } catch (Exception e) {
            log.warn("扫描 {} 目录失败: {}", spiDir, e.getMessage());
            return List.of();
        }

        if (jars.isEmpty()) {
            return List.of();
        }

        URLClassLoader classLoader = new URLClassLoader(jars.toArray(new URL[0]),
                ModelClassLoader.class.getClassLoader());

        java.util.ServiceLoader<T> loader = java.util.ServiceLoader.load(spiType, classLoader);
        List<T> impls = new ArrayList<>();
        loader.forEach(impls::add);

        if (impls.isEmpty()) {
            log.warn("在 {} 的 jar 中未找到 {} 的 SPI 实现", spiDir, spiType.getName());
        }
        return impls;
    }
}
