package com.kudosol.ai.inference.source;

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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class ModelSourceDownloader implements ApplicationRunner {

    private final InferenceProperties inferenceProperties;
    private final S3Properties s3Properties;

    @Override
    public void run(ApplicationArguments args) {
        if (inferenceProperties.getModelSources().isEmpty()) {
            log.info("未配置模型来源 (inference.model-sources)");
            return;
        }

        Path modelDir = Path.of(inferenceProperties.getModelDir());
        try {
            Files.createDirectories(modelDir);
        } catch (IOException e) {
            throw new IllegalStateException("创建模型目录失败: " + e.getMessage(), e);
        }

        boolean hasS3Source = inferenceProperties.getModelSources().stream()
                .anyMatch(s -> s.startsWith("s3://"));
        S3Client s3Client = null;
        if (hasS3Source) {
            if (!s3Properties.isEnabled()) {
                throw new IllegalStateException("model-sources 包含 s3:// 但 inference.s3.enabled=false");
            }
            s3Client = buildS3Client();
        }

        try {
            for (String source : inferenceProperties.getModelSources()) {
                try {
                    downloadAndExtract(source, modelDir, s3Client);
                } catch (Exception e) {
                    log.warn("模型下载失败，跳过: {} — {}", source, e.getMessage());
                }
            }
        } finally {
            if (s3Client != null) s3Client.close();
        }
    }

    private void downloadAndExtract(String source, Path modelDir, S3Client s3Client) {
        String modelName = deriveModelName(source);
        log.info("下载模型包: {}", source);

        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("model-download-", getTempSuffix(source));

            if (source.startsWith("s3://")) {
                downloadFromS3(s3Client, source, tempFile);
            } else if (source.startsWith("http://") || source.startsWith("https://")) {
                downloadFromHttp(source, tempFile);
            } else {
                throw new IllegalArgumentException("不支持的模型来源格式: " + source);
            }

            extract(tempFile, modelDir, modelName);
            log.info("模型包 [{}] 下载并解压完成", modelName);
        } catch (S3Exception e) {
            throw new IllegalStateException("从 S3 下载失败 %s: %s".formatted(source, e.getMessage()), e);
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("下载或解压失败 %s: %s".formatted(source, e.getMessage()), e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void downloadFromS3(S3Client s3Client, String source, Path target) throws IOException {
        String s3Key = source.substring("s3://".length());
        GetObjectRequest request = GetObjectRequest.builder()
                .bucket(s3Properties.getBucket())
                .key(s3Key)
                .build();

        try (ResponseInputStream<GetObjectResponse> s3Stream = s3Client.getObject(request);
             BufferedOutputStream out = new BufferedOutputStream(Files.newOutputStream(target))) {
            s3Stream.transferTo(out);
        }
    }

    private void downloadFromHttp(String url, Path target) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
            throw new IOException("HTTP 下载失败，状态码: " + response.statusCode() + "，响应: " + body);
        }
        Files.write(target, response.body());
    }

    private void extract(Path archiveFile, Path modelDir, String modelName) throws IOException {
        String fileName = archiveFile.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".zip")) {
            extractZip(archiveFile, modelDir, modelName);
        } else if (fileName.endsWith(".tar.gz") || fileName.endsWith(".tgz")) {
            extractTarGz(archiveFile, modelDir, modelName);
        } else {
            throw new IllegalArgumentException("不支持的压缩格式: " + fileName + "，支持 .tar.gz/.tgz/.zip");
        }
    }

    private void extractTarGz(Path tarGzFile, Path modelDir, String modelName) throws IOException {
        Path targetDir = modelDir.resolve(modelName);
        prepareTargetDir(targetDir);

        try (TarArchiveInputStream tarStream = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(tarGzFile)))) {

            TarArchiveEntry entry;
            while ((entry = tarStream.getNextTarEntry()) != null) {
                if (!tarStream.canReadEntryData(entry)) {
                    log.warn("无法读取 tar 条目: {}", entry.getName());
                    continue;
                }
                copyEntry(entry.getName(), tarStream, targetDir, modelDir, modelName);
            }
        }
    }

    private void extractZip(Path zipFile, Path modelDir, String modelName) throws IOException {
        Path targetDir = modelDir.resolve(modelName);
        prepareTargetDir(targetDir);

        try (ZipInputStream zipStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                copyEntry(entry.getName(), zipStream, targetDir, modelDir, modelName);
            }
        }
    }

    private void copyEntry(String entryName, java.io.InputStream stream, Path targetDir, Path modelDir, String modelName) throws IOException {
        if (entryName.contains("..") || entryName.startsWith("/")) {
            throw new IOException("不安全的条目路径: " + entryName);
        }

        String relativePath = stripTopLevelDir(entryName, modelName);
        if (relativePath == null || relativePath.isEmpty()) return;

        Path entryTarget = targetDir.resolve(relativePath).normalize();
        if (!entryTarget.startsWith(targetDir)) {
            throw new IOException("条目路径逃逸: " + entryName);
        }

        if (entryName.endsWith("/")) {
            Files.createDirectories(entryTarget);
        } else {
            Files.createDirectories(entryTarget.getParent());
            Files.copy(stream, entryTarget);
        }
    }

    private void prepareTargetDir(Path targetDir) throws IOException {
        if (Files.exists(targetDir)) {
            deleteRecursively(targetDir);
        }
        Files.createDirectories(targetDir);
    }

    private String stripTopLevelDir(String entryName, String modelName) {
        if (entryName.startsWith(modelName + "/")) {
            return entryName.substring(modelName.length() + 1);
        }
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

    private String deriveModelName(String source) {
        String fileName = stripQueryString(source.substring(source.lastIndexOf('/') + 1));
        if (fileName.endsWith(".tar.gz")) return fileName.substring(0, fileName.length() - 7);
        if (fileName.endsWith(".tgz")) return fileName.substring(0, fileName.length() - 4);
        if (fileName.endsWith(".zip")) return fileName.substring(0, fileName.length() - 4);
        return fileName;
    }

    private String getTempSuffix(String source) {
        String lower = stripQueryString(source).toLowerCase();
        if (lower.endsWith(".zip")) return ".zip";
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) return ".tar.gz";
        return ".pkg";
    }

    private String stripQueryString(String s) {
        int idx = s.indexOf('?');
        return idx >= 0 ? s.substring(0, idx) : s;
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
}
