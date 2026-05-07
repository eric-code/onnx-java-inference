FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# 解压 fat jar，解决 ONNX Runtime 本地库在嵌套 jar 中无法加载的问题
COPY target/onnx-java-inference-base-*-exec.jar app.jar
RUN java -Djarmode=layertools -jar app.jar extract --destination extracted \
    && rm app.jar

# 从 onnxruntime jar 中提取 Linux x64 本地库到 java.library.path
RUN ONNX_JAR=$(find extracted/dependencies/BOOT-INF/lib -name 'onnxruntime-*.jar' | head -1) \
    && mkdir -p /app/native \
    && unzip -jo "$ONNX_JAR" 'ai/onnxruntime/native/linux-x64/*' -d /app/native/ \
    && rm -f /app/native/*.dSYM /app/native/*.dll /app/native/*.dylib 2>/dev/null || true

ENV JAVA_OPTS="-Djava.library.path=/app/native"

EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -cp \"extracted/dependencies:extracted/spring-boot-loader:extracted/snapshot-dependencies:extracted/application\" org.springframework.boot.loader.launch.JarLauncher \"$@\"", "--"]
