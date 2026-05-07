package com.kudosol.ai.inference.s3;

import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.config.S3Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class S3Downloader implements ApplicationRunner {

    private final S3Properties s3Properties;
    private final InferenceProperties inferenceProperties;

    @Override
    public void run(ApplicationArguments args) {
        if (!s3Properties.isEnabled()) {
            log.info("S3 模型下载未启用");
            return;
        }

        if (s3Properties.getModels().isEmpty()) {
            log.warn("S3 已启用但未配置模型列表 (inference.s3.models)");
            return;
        }

        try (S3Client s3Client = buildS3Client()) {
            Path modelDir = Path.of(inferenceProperties.getModelDir());
            Files.createDirectories(modelDir);

            for (String modelKey : s3Properties.getModels()) {
                downloadAndExtract(s3Client, modelDir, modelKey);
            }
        } catch (Exception e) {
            throw new IllegalStateException("S3 模型下载失败: " + e.getMessage(), e);
        }
    }

    private S3Client buildS3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(s3Properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                s3Properties.getAccessKey(),
                                s3Properties.getSecretKey())));

        if (StringUtils.hasText(s3Properties.getEndpoint())) {
            builder.endpointOverride(URI.create(s3Properties.getEndpoint()))
                    .serviceConfiguration(cfg -> cfg.pathStyleAccessEnabled(s3Properties.isPathStyleAccess()));
        }

        return builder.build();
    }

    private void downloadAndExtract(S3Client s3Client, Path modelDir, String modelKey) {
        String modelName = deriveModelName(modelKey);
        log.info("从 S3 下载模型包: s3://{}/{}", s3Properties.getBucket(), modelKey);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("model-download-", ".tar.gz");
            downloadToFile(s3Client, modelKey, tempFile);
            extractTarGz(tempFile, modelDir, modelName);
            log.info("模型包 [{}] 下载并解压完成", modelName);
        } catch (S3Exception e) {
            throw new IllegalStateException("从 S3 下载 %s 失败: %s".formatted(modelKey, e.getMessage()), e);
        } catch (IOException e) {
            throw new IllegalStateException("解压模型包 %s 失败: %s".formatted(modelKey, e.getMessage()), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void downloadToFile(S3Client s3Client, String modelKey, Path target) throws IOException {
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(modelKey)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(request);
             BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            s3Stream.transferTo(out);
        }
    }

    private void extractTarGz(Path tarGzFile, Path modelDir, String modelName) throws IOException {
        Path targetDir = modelDir.resolve(modelName);
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }

        try (TarArchiveInputStream tarStream = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(tarGzFile)))) {

            TarArchiveEntry entry;
            while ((entry = tarStream.getNextTarEntry()) != null) {
                if (!tarStream.canReadEntryData(entry)) {
                    log.warn("无法读取 tar 条目: {}", entry.getName());
                    continue;
                }

                String entryName = entry.getName();
                if (entryName.contains("..") || entryName.startsWith("/")) {
                    throw new IOException("不安全的 tar 条目路径: " + entryName);
                }

                // strip 顶层目录，如 sample-model/model.onnx -> model.onnx
                String relativePath = stripTopLevelDir(entryName, modelName);
                if (relativePath == null || relativePath.isEmpty()) {
                    continue;
                }

                Path entryTarget = modelDir.resolve(modelName).resolve(relativePath).normalize();
                if (!entryTarget.startsWith(modelDir.resolve(modelName))) {
                    throw new IOException("条目路径逃逸: " + entryName);
                }

                if (entry.isDirectory()) {
                    Files.createDirectories(entryTarget);
                } else {
                    Files.createDirectories(entryTarget.getParent());
                    Files.copy(tarStream, entryTarget);
                }
            }
        }
    }

    private String stripTopLevelDir(String entryName, String modelName) {
        if (entryName.startsWith(modelName + "/")) {
            return entryName.substring(modelName.length() + 1);
        }
        // tar 包内无顶层目录，直接使用
        return entryName;
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private String deriveModelName(String s3Key) {
        String fileName = s3Key.substring(s3Key.lastIndexOf('/') + 1);
        if (fileName.endsWith(".tar.gz")) {
            return fileName.substring(0, fileName.length() - 7);
        }
        if (fileName.endsWith(".tgz")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}
