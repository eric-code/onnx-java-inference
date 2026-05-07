package com.kudosol.ai.inference.engine;

import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.Preprocessor;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ModelClassLoader {

    public static Preprocessor loadPreprocessor(Path modelDir) {
        return loadSpi(modelDir, "preprocessor", Preprocessor.class);
    }

    public static Postprocessor loadPostprocessor(Path modelDir) {
        return loadSpi(modelDir, "postprocessor", Postprocessor.class);
    }

    private static <T> T loadSpi(Path modelDir, String subDir, Class<T> spiType) {
        Path spiDir = modelDir.resolve(subDir);
        if (!Files.isDirectory(spiDir)) {
            throw new IllegalStateException(
                    "模型目录 %s 下缺少 %s/ 子目录".formatted(modelDir, subDir));
        }

        List<URL> jars = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(spiDir, "*.jar")) {
            for (Path jar : stream) {
                jars.add(jar.toUri().toURL());
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描 %s 目录失败: %s".formatted(spiDir, e.getMessage()), e);
        }

        if (jars.isEmpty()) {
            throw new IllegalStateException(
                    "%s/ 目录下未找到 jar 文件: %s".formatted(subDir, spiDir));
        }

        URLClassLoader classLoader = new URLClassLoader(jars.toArray(new URL[0]),
                ModelClassLoader.class.getClassLoader());

        java.util.ServiceLoader<T> loader = java.util.ServiceLoader.load(spiType, classLoader);
        T impl = loader.findFirst().orElse(null);
        if (impl == null) {
            throw new IllegalStateException(
                    "在 %s 的 jar 中未找到 %s 的实现（请确保 META-INF/services 配置正确）".formatted(spiDir, spiType.getName()));
        }
        return impl;
    }
}
