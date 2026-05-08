FROM eclipse-temurin:21-jre-alpine AS builder

WORKDIR /app
COPY target/onnx-java-inference-base-*-exec.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted \
    && rm app.jar

# --- runtime image ---
FROM eclipse-temurin:21-jre

WORKDIR /app

# 依赖层（变更最少，缓存命中率最高）
COPY --from=builder /app/extracted/dependencies/ ./
# Spring Boot loader 层（JarLauncher 等启动类）
COPY --from=builder /app/extracted/spring-boot-loader/ ./
# snapshot 依赖层
COPY --from=builder /app/extracted/snapshot-dependencies/ ./
# 应用代码层（变更最多，但体积最小）
COPY --from=builder /app/extracted/application/ ./

# 从 onnxruntime jar 中提取 Linux x64 本地库
RUN ONNX_JAR=$(find BOOT-INF/lib -name 'onnxruntime-*.jar' | head -1) \
    && mkdir -p /app/native \
    && unzip -jo "$ONNX_JAR" 'ai/onnxruntime/native/linux-x64/*' -d /app/native/ \
    && rm -f /app/native/*.dSYM /app/native/*.dll /app/native/*.dylib 2>/dev/null || true

ENV JAVA_OPTS="-Djava.library.path=/app/native"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
