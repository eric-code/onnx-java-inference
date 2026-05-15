package com.kudosol.ai.inference.source;

import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.config.S3Properties;
import com.kudosol.ai.inference.exception.BadRequestException;
import jakarta.annotation.PostConstruct;
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
import org.springframework.web.util.UriComponentsBuilder;
import org.yaml.snakeyaml.Yaml;
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
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Slf4j
@Component
@RequiredArgsConstructor
@Order(1)
public class ModelSourceDownloader implements ApplicationRunner {

    private static final String STAGING_PREFIX = ".staging-";

    private final InferenceProperties inferenceProperties;
    private final S3Properties s3Properties;

    private HttpClient httpClient;

    @PostConstruct
    void init() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(inferenceProperties.getDownloadTimeout())
                .build();
    }

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

        cleanupStagingResidue(modelDir);

        boolean hasS3Source = inferenceProperties.getModelSources().stream()
                .anyMatch(s -> s.startsWith("s3://"));
        S3Client s3Client = null;
        if (hasS3Source) {
            if (!s3Properties.isEnabled()) {
                throw new BadRequestException("model-sources 包含 s3:// 但 inference.s3.enabled=false");
            }
            s3Client = buildS3Client();
        }

        try {
            for (String source : inferenceProperties.getModelSources()) {
                try {
                    downloadWithRetry(source, modelDir, s3Client);
                } catch (Exception e) {
                    log.error("模型下载最终失败，跳过: {} — {}", source, e.getMessage());
                }
            }
        } finally {
            if (s3Client != null) s3Client.close();
        }
    }

    private void downloadWithRetry(String source, Path modelDir, S3Client s3Client) {
        int maxAttempts = inferenceProperties.getDownloadRetryCount() + 1;
        Duration baseDelay = inferenceProperties.getDownloadRetryDelay();

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                downloadAndExtract(source, modelDir, s3Client);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts) {
                    throw new IllegalStateException(
                            "模型下载失败 (已重试 %d 次): %s — %s".formatted(
                                    inferenceProperties.getDownloadRetryCount(), source, e.getMessage()), e);
                }
                long delayMs = baseDelay.toMillis() * (1L << (attempt - 1));
                log.warn("模型下载失败 (尝试 {}/{}): {} — {}，{}ms 后重试",
                        attempt, maxAttempts, source, e.getMessage(), delayMs);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("模型下载被中断: " + source, ie);
                }
            }
        }
    }

    private void downloadAndExtract(String source, Path modelDir, S3Client s3Client) {
        log.info("下载模型包: {}", source);

        Path tempFile = null;
        Path staging = null;
        try {
            tempFile = Files.createTempFile("model-download-", ".pkg");

            if (source.startsWith("s3://")) {
                downloadFromS3(s3Client, source, tempFile);
            } else if (source.startsWith("http://") || source.startsWith("https://")) {
                downloadFromHttp(source, tempFile);
            } else {
                throw new BadRequestException("不支持的模型来源格式: " + source);
            }

            staging = Files.createTempDirectory(modelDir, STAGING_PREFIX);
            extract(tempFile, staging);

            Path metaFile = findModelMeta(staging);
            if (metaFile == null) {
                throw new BadRequestException("归档中未找到 model.yml");
            }
            String modelName = readModelName(metaFile);
            if (!StringUtils.hasText(modelName)) {
                throw new BadRequestException("model.yml 中 name 字段缺失或为空");
            }

            Path target = modelDir.resolve(modelName);
            if (Files.exists(target)) {
                deleteRecursively(target);
            }
            Files.move(metaFile.getParent(), target, StandardCopyOption.ATOMIC_MOVE);
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
            if (staging != null && Files.exists(staging)) {
                try {
                    deleteRecursively(staging);
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
        URI uri = encodeQueryParams(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(inferenceProperties.getDownloadTimeout())
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request,
                HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(target);
            throw new IOException("HTTP 下载失败，状态码: " + response.statusCode());
        }
    }

    private void extract(Path archiveFile, Path targetDir) throws IOException {
        ArchiveType type = sniffArchiveType(archiveFile);
        if (type == null) {
            throw new BadRequestException("不支持的压缩格式（按文件头识别），仅支持 .zip / .tar.gz / .tgz");
        }
        switch (type) {
            case ZIP -> extractZip(archiveFile, targetDir);
            case TAR_GZ -> extractTarGz(archiveFile, targetDir);
        }
    }

    private ArchiveType sniffArchiveType(Path file) throws IOException {
        byte[] header = new byte[4];
        try (InputStream in = Files.newInputStream(file)) {
            int n = in.read(header);
            if (n < 2) return null;
        }
        if (header[0] == 'P' && header[1] == 'K') return ArchiveType.ZIP;
        if ((header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) return ArchiveType.TAR_GZ;
        return null;
    }

    private void extractTarGz(Path tarGzFile, Path targetDir) throws IOException {
        try (TarArchiveInputStream tarStream = new TarArchiveInputStream(
                new GzipCompressorInputStream(Files.newInputStream(tarGzFile)))) {

            TarArchiveEntry entry;
            while ((entry = tarStream.getNextTarEntry()) != null) {
                if (!tarStream.canReadEntryData(entry)) {
                    log.warn("无法读取 tar 条目: {}", entry.getName());
                    continue;
                }
                writeEntry(targetDir, entry.getName(), entry.isDirectory(), tarStream);
            }
        }
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zipStream = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zipStream.getNextEntry()) != null) {
                writeEntry(targetDir, entry.getName(), entry.isDirectory(), zipStream);
            }
        }
    }

    private void writeEntry(Path targetDir, String entryName, boolean isDirectory, InputStream stream) throws IOException {
        if (entryName.contains("..") || entryName.startsWith("/")) {
            throw new IOException("不安全的条目路径: " + entryName);
        }

        Path entryTarget = targetDir.resolve(entryName).normalize();
        if (!entryTarget.startsWith(targetDir)) {
            throw new IOException("条目路径逃逸: " + entryName);
        }

        if (isDirectory) {
            Files.createDirectories(entryTarget);
        } else {
            Files.createDirectories(entryTarget.getParent());
            Files.copy(stream, entryTarget);
        }
    }

    private Path findModelMeta(Path staging) throws IOException {
        try (var stream = Files.walk(staging)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> "model.yml".equals(p.getFileName().toString()))
                    .findFirst()
                    .orElse(null);
        }
    }

    @SuppressWarnings("unchecked")
    private String readModelName(Path metaFile) throws IOException {
        try (InputStream in = Files.newInputStream(metaFile)) {
            Object raw = new Yaml().load(in);
            if (!(raw instanceof Map)) return null;
            Object name = ((Map<String, Object>) raw).get("name");
            return name != null ? name.toString() : null;
        }
    }

    private void cleanupStagingResidue(Path modelDir) {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(modelDir, STAGING_PREFIX + "*")) {
            for (Path residue : stream) {
                try {
                    deleteRecursively(residue);
                    log.info("清理上次残留的临时解压目录: {}", residue.getFileName());
                } catch (IOException e) {
                    log.warn("清理临时解压目录失败: {} — {}", residue.getFileName(), e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("扫描临时解压残留失败: {}", e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
    }

    private S3Client buildS3Client() {
        S3ClientBuilder builder = S3Client.builder()
                .overrideConfiguration(cfg -> cfg
                        .apiCallTimeout(inferenceProperties.getDownloadTimeout())
                        .apiCallAttemptTimeout(inferenceProperties.getDownloadTimeout()))
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

    private URI encodeQueryParams(String url) {
        return UriComponentsBuilder.fromHttpUrl(url).build().encode().toUri();
    }

    private enum ArchiveType {
        ZIP, TAR_GZ
    }
}
