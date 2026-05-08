# ONNX Java Inference

基于 Spring Boot + ONNX Runtime Java 的 ONNX 模型推理服务，支持通过 SPI 接口动态挂载模型的前后处理代码，内置通用前后处理器，支持
S3 模型包动态加载。

## 架构

采用**基础镜像 + 模型包**设计，推理框架与模型解耦：

```
基础镜像 onnx-java-inference-base
  ├── JRE 21 (Alpine)
  ├── ONNX Runtime Java
  └── 推理框架
        ├── Spring Boot HTTP 服务
        ├── S3 模型下载器
        ├── 模型加载引擎
        ├── 内置通用前后处理器（DefaultPreprocessor / DefaultPostprocessor）
        ├── 前后处理 SPI（Preprocessor / Postprocessor 接口）
        └── 统一推理 API

    ↓ 两种部署方式

方式一：模型打包进镜像          方式二：S3 动态拉取
  ├── model-a/                   服务启动时从 S3 下载模型包
  │   ├── model.onnx             → 解压到 /models/
  │   ├── model.yml              → 自动加载
  │   ├── preprocessor/ (可选)
  │   └── postprocessor/ (可选)
  └── model-b/ ...
```

基础镜像稳定少变，模型通过镜像打包或 S3 动态加载两种方式部署，新增模型无需修改基础镜像。

### 模型加载流程

```
Spring Boot 启动
    ↓
S3Downloader (@Order(1))
    - S3 未启用 → 跳过
    - S3 启用 → 从 S3 下载 tar.gz → 解压到 /models/
    ↓
ModelManager (@Order(2))
    - 扫描 /models/ 下的子目录
    - 对每个子目录：
        1. 解析 model.yml → ModelMeta
        2. 加载 model.onnx → OrtSession
        3. 加载前后处理器（自定义 jar 优先，无 jar 则使用内置默认处理器）
        4. 调用 init(ModelMeta) 传入模型元数据
        5. 封装为 ModelContainer
```

### 内置通用前后处理器

框架内置 `DefaultPreprocessor` 和 `DefaultPostprocessor`，根据 `model.yml` 中的 inputs/outputs 元数据自动工作，无需编写任何
Java 代码：

- **DefaultPreprocessor**：将 JSON 请求体转为 OnnxTensor，支持 float32/int64/int32 类型、多输入、batch
- **DefaultPostprocessor**：将 OnnxTensor 输出透传为可 JSON 序列化的 Java 数组，支持多输出

当模型目录下存在 `preprocessor/` 或 `postprocessor/` 子目录且包含 jar 时，优先使用自定义实现。

### 多模型加载

框架启动时自动扫描 `/models/` 目录下的所有子目录，每个子目录视为一个独立模型，全部加载到同一个 JVM 中运行：

```
/models/
├── model-a/          # 自动加载
│   ├── model.onnx
│   ├── model.yml
│   ├── preprocessor/      # 可选，有则用自定义 jar
│   └── postprocessor/     # 可选，有则用自定义 jar
├── model-b/          # 自动加载
│   ├── model.onnx
│   └── model.yml          # 无 preprocessor/postprocessor 目录，使用内置默认处理器
└── model-c/          # 自动加载
    ├── model.onnx
    ├── model.yml
    ├── preprocessor/
    └── postprocessor/
```

通过 URL 中的模型名路由到对应模型：

```bash
curl -X POST http://localhost:8080/infer/model-a \
  -H "Content-Type: application/json" \
  -d '{"float_input": [1.0, 2.0, 3.0, 4.0]}'
```

### 部署形态

| 部署方式    | 说明            | 适合场景           |
|---------|---------------|----------------|
| 镜像打包    | 模型文件 COPY 进镜像 | 本地开发、无 S3 环境   |
| S3 动态拉取 | 启动时从 S3 下载模型包 | 生产环境、CI/CD 自动化 |
| 合设      | 多模型同一容器       | 模型少、调用量低       |
| 拆分      | 每模型独立容器       | 模型多、并发高、需独立伸缩  |

两种部署方式和两种部署形态可以自由组合。

## 项目结构

```
onnx-java-inference/
├── pom.xml                              # 根 POM
├── base/                                # 基础推理框架
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../inference/
│       ├── InferenceApplication.java
│       ├── spi/
│       │   ├── Preprocessor.java        # 前处理 SPI 接口
│       │   ├── Postprocessor.java       # 后处理 SPI 接口
│       │   ├── ModelMeta.java           # 模型元数据
│       │   ├── TensorMeta.java          # 张量元数据
│       │   ├── DefaultPreprocessor.java # 内置通用前处理器
│       │   └── DefaultPostprocessor.java# 内置通用后处理器
│       ├── engine/
│       │   ├── ModelManager.java        # 模型生命周期管理
│       │   ├── ModelClassLoader.java    # 动态加载前后处理 jar
│       │   ├── ModelContainer.java      # 单模型运行时容器
│       │   └── InferenceEngine.java     # 推理执行引擎
│       ├── controller/
│       │   └── InferenceController.java # REST API
│       ├── config/
│       │   ├── InferenceProperties.java
│       │   └── S3Properties.java        # S3 配置属性
│       └── s3/
│           └── S3Downloader.java        # S3 模型下载器
└── sample-model/                        # 示例模型
    ├── pom.xml
    ├── Dockerfile
    ├── model.yml
    └── src/main/java/.../sample/
        ├── preprocessor/SamplePreprocessor.java
        └── postprocessor/SamplePostprocessor.java
```

## API

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/infer/{modelName}` | 执行推理，请求体为原始数据，返回 JSON 结果 |
| GET | `/models` | 列出所有已加载模型 |
| GET | `/models/{name}` | 查看模型详情（输入输出定义） |
| GET | `/actuator/health` | 健康检查 |

### 推理请求示例

使用内置默认处理器时，请求体为 JSON 格式：

```bash
# 单条推理
curl -X POST http://localhost:8080/infer/sample-model \
  -H "Content-Type: application/json" \
  -d '{"float_input": [1.0, 2.0, 3.0, 4.0]}'

# 批量推理
curl -X POST http://localhost:8080/infer/sample-model \
  -H "Content-Type: application/json" \
  -d '{"float_input": [[1.0, 2.0, 3.0, 4.0], [5.0, 6.0, 7.0, 8.0]]}'

# 列出已加载模型
curl http://localhost:8080/models

# 查看模型详情
curl http://localhost:8080/models/sample-model
```

使用自定义前后处理器时，请求体格式由处理器实现决定。

## SPI 接口

每个模型可通过实现 `Preprocessor` 和 `Postprocessor` 接口定义自定义前后处理逻辑，打包为独立 jar，运行时由框架通过
ServiceLoader 动态加载。未提供自定义 jar 时，框架自动使用内置通用处理器。

### Preprocessor

```java
public interface Preprocessor {
    /**
     * 初始化，传入模型元数据（输入输出的名称、类型、shape）。
     * 在 process() 之前调用。
     */
    default void init(ModelMeta meta) {}

    /**
     * 将原始请求数据转为 ONNX 模型输入张量。
     */
    Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception;
}
```

### Postprocessor

```java
public interface Postprocessor {
    /**
     * 初始化，传入模型元数据（输入输出的名称、类型、shape）。
     * 在 process() 之前调用。
     */
    default void init(ModelMeta meta) {}

    /**
     * 将 ONNX 模型输出张量转为可 JSON 序列化的结果。
     */
    Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception;
}
```

### ModelMeta

```java
public class ModelMeta {
    private String name;
    private String version;
    private String description;
    private List<TensorMeta> inputs;
    private List<TensorMeta> outputs;
}

public class TensorMeta {
    private String name;        // 张量名称，如 "float_input"
    private String type;        // 数据类型，如 "float32"、"int64"、"int32"
    private List<Long> shape;   // 形状，如 [1, 4]，动态维度用 -1 或 0
}
```

### SPI 安全性

当前方案中，SPI 加载的 jar 与框架运行在同一个 JVM 中，拥有相同权限。安全边界建立在 **Docker 镜像构建流程** 或 **S3 上传流程
** 上：前后处理 jar 是在构建模型镜像时 COPY 进去的，或通过受控的 S3 上传流程放入模型包的。如果攻击者能注入恶意
jar，说明他已经能修改构建流程或 S3 存储，此时直接篡改基础镜像危害更大。因此在内部使用场景下 SPI 加载是安全的。

如果未来需要开放模型接入（如第三方提交模型），可通过以下方式增强：

- **代码签名验证**：构建时用私钥签名 jar，框架加载前用公钥验签，只加载签名合法的 jar
- **独立进程隔离**：将推理放到子进程中，通过 gRPC 通信，子进程用 cgroup 限制资源
- **ClassLoader 白名单**：自定义 ClassLoader 拦截 `Runtime`、`ProcessBuilder` 等危险类的加载

## 新增模型

以添加一个名为 `my-model` 的模型为例：

### 方式一：使用内置默认处理器（推荐）

适用于常见模型（float/int 数组输入输出），无需编写任何 Java 代码。

#### 1. 准备模型包

```
my-model/
├── model.onnx
└── model.yml
```

#### 2. 编写 model.yml

```yaml
name: my-model
version: "1.0"
description: 我的模型
inputs:
  - name: input
    type: float32
    shape: [-1, 4]       # -1 表示动态 batch 维度
outputs:
  - name: output
    type: float32
```

model.yml 中的 inputs/outputs 信息会传入前后处理器，默认处理器据此自动构建张量。

#### 3. 部署

**镜像打包方式**：

```dockerfile
FROM harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
```

**S3 动态拉取方式**：

将模型目录打包为 tar.gz 上传到 S3：

```bash
tar czf my-model.tar.gz my-model/
aws s3 cp my-model.tar.gz s3://my-bucket/models/my-model.tar.gz
```

启动服务时配置环境变量：

```bash
docker run -d -p 8080:8080 \
  -e S3_ENABLED=true \
  -e S3_ENDPOINT=https://s3.amazonaws.com \
  -e S3_BUCKET=my-bucket \
  -e S3_MODELS=models/my-model.tar.gz \
  -e S3_ACCESS_KEY=xxx \
  -e S3_SECRET_KEY=xxx \
  harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0
```

### 方式二：使用自定义前后处理器

适用于需要特殊前后处理逻辑的模型（如图像 resize/normalize、文本 tokenize 等）。

#### 1. 准备模型目录

```
my-model/
├── model.onnx
├── model.yml
├── preprocessor/
│   └── preprocessor.jar
└── postprocessor/
    └── postprocessor.jar
```

#### 2. 编写 model.yml

```yaml
name: my-model
version: "1.0"
description: 我的模型
inputs:
  - name: input
    type: float32
    shape: [1, 3, 224, 224]
outputs:
  - name: output
    type: float32
    shape: [1, 1000]
```

#### 3. 实现 Preprocessor

```java
package com.example.myModel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.Preprocessor;
import com.kudosol.ai.inference.spi.TensorMeta;
import java.nio.FloatBuffer;
import java.util.Map;

public class MyPreprocessor implements Preprocessor {

    private String inputName;
    private int featureCount;

    @Override
    public void init(ModelMeta meta) {
        TensorMeta input = meta.getInputs().get(0);
        this.inputName = input.getName();
        this.featureCount = input.getShape().getLast().intValue();
    }

    @Override
    public Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception {
        // 实现你的前处理逻辑
        float[] values = ...;
        long[] shape = {1, featureCount};
        OnnxTensor tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape);
        return Map.of(inputName, tensor);
    }
}
```

#### 4. 实现 Postprocessor

```java
package com.example.myModel;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.ModelMeta;
import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.TensorMeta;
import java.util.Map;

public class MyPostprocessor implements Postprocessor {

    private String outputName;

    @Override
    public void init(ModelMeta meta) {
        TensorMeta output = meta.getOutputs().get(0);
        this.outputName = output.getName();
    }

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) throws Exception {
        OnnxTensor tensor = output.get(outputName);
        return Map.of(outputName, tensor.getValue());
    }
}
```

#### 5. 配置 ServiceLoader

在 jar 的 `META-INF/services/` 目录下创建文件：

**META-INF/services/com.kudosol.ai.inference.spi.Preprocessor**
```
com.example.myModel.MyPreprocessor
```

**META-INF/services/com.kudosol.ai.inference.spi.Postprocessor**
```
com.example.myModel.MyPostprocessor
```

#### 6. 部署

**镜像打包方式**：

```dockerfile
FROM harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
COPY preprocessor.jar /models/my-model/preprocessor/preprocessor.jar
COPY postprocessor.jar /models/my-model/postprocessor/postprocessor.jar
```

**S3 动态拉取方式**：将完整模型目录（含 jar）打包为 tar.gz 上传 S3。

## S3 模型包格式

上传到 S3 的 tar.gz 包应包含以模型名命名的顶层目录：

```
my-model.tar.gz
└── my-model/
    ├── model.onnx
    ├── model.yml
    ├── preprocessor/          ← 可选
    │   └── preprocessor.jar
    └── postprocessor/         ← 可选
        └── postprocessor.jar
```

框架解压时自动识别顶层目录名作为模型名，解压到 `/models/<模型名>/` 下。

## 构建与部署

### 前置条件

- JDK 21+
- Maven 3.8+
- Docker

### 构建并推送基础镜像

```bash
# 1. 编译推理框架
mvn clean package -pl base -am -DskipTests

# 2. 构建基础 Docker 镜像
docker build -t harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0 ./base

# 3. 推送到 Harbor
docker push harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0
```

### 构建并推送模型镜像

```bash
# 1. 编译示例模型的前后处理 jar
mvn clean package -pl sample-model -am -DskipTests

# 2. 构建模型 Docker 镜像
docker build -t harbor.tianyushuju.com/skyease/onnx-java-inference-sample:1.0.0 ./sample-model

# 3. 推送到 Harbor
docker push harbor.tianyushuju.com/skyease/onnx-java-inference-sample:1.0.0
```

### 运行

```bash
# 方式一：镜像打包模式
docker run -d -p 8080:8080 harbor.tianyushuju.com/skyease/onnx-java-inference-sample:1.0.0

# 方式二：S3 动态拉取模式
docker run -d -p 8080:8080 \
  -e S3_ENABLED=true \
  -e S3_ENDPOINT=https://s3.amazonaws.com \
  -e S3_BUCKET=my-bucket \
  -e S3_MODELS=models/sample-model.tar.gz \
  -e S3_ACCESS_KEY=xxx \
  -e S3_SECRET_KEY=xxx \
  harbor.tianyushuju.com/skyease/onnx-java-inference-base:1.0.0

# 方式三：本地 jar 运行（自定义模型目录）
java -jar base/target/onnx-java-inference-base-1.0.0-SNAPSHOT-exec.jar \
  --inference.model-dir=/path/to/models
```

### 环境变量

| 变量                       | 默认值       | 说明                                 |
|--------------------------|-----------|------------------------------------|
| `INFERENCE_THREAD_COUNT` | 4         | ONNX Runtime 推理线程数                 |
| `SERVER_PORT`            | 8080      | HTTP 服务端口                          |
| `S3_ENABLED`             | false     | 是否启用 S3 模型下载                       |
| `S3_ENDPOINT`            | -         | S3 兼容端点（MinIO 等需设置）                |
| `S3_REGION`              | us-east-1 | S3 Region                          |
| `S3_BUCKET`              | -         | S3 Bucket 名称                       |
| `S3_ACCESS_KEY`          | -         | S3 Access Key                      |
| `S3_SECRET_KEY`          | -         | S3 Secret Key                      |
| `S3_PATH_STYLE_ACCESS`   | false     | 是否使用 Path Style 访问（MinIO 需设为 true） |
| `S3_MODELS`              | -         | 模型包 S3 key 列表，逗号分隔                 |

## 注意事项

- **输入类型校验**：应用启动时会打印每个模型的输入输出信息（名称、类型、形状），前后处理实现必须与模型定义匹配，否则运行时将报错
- **张量类型**：ONNX 模型通常使用 `float32`，Java 端构建 `OnnxTensor` 时需用 `FloatBuffer` 而非 `DoubleBuffer`，类型不匹配会直接报错
- **多维数组**：Java 端用一维数组 + `shape` 数组表示多维张量，例如 `[1, 3, 224, 224]` 对应长度为 `1*3*224*224` 的一维 float 数组
- **线程安全**：`OrtSession` 线程安全可复用，前后处理类应设计为无状态
- **S3 兼容性**：S3 下载功能兼容 AWS S3 和 MinIO 等 S3 兼容存储，使用 MinIO 时需设置 `S3_ENDPOINT` 和
  `S3_PATH_STYLE_ACCESS=true`

## TODO

### 算子编排系统

声明式 DAG 算子编排系统，替代自定义 JAR 前后处理器。详见 [docs/operator-pipeline.md](docs/operator-pipeline.md)。

### ~~推理日志 WebSocket 推送~~ (已完成)

通过 **STOMP over WebSocket** 将推理日志实时推送给订阅客户端：

- **方案选型**：使用 STOMP 协议而非原生 WebSocket，因为日志天然是多主题的（按模型、按级别分流），STOMP 的 pub/sub 语义直接匹配
- **Spring 集成**：基于 `spring-boot-starter-websocket` + `SimpMessagingTemplate`，几行代码即可广播日志
- **订阅主题设计**：
    - `/topic/logs` — 全局推理日志
    - `/topic/logs/{modelName}` — 按模型过滤的日志
    - `/topic/errors` — 仅错误日志
- **日志接入**：`InferenceEngine` 在前处理、推理、后处理各阶段通过 `InferenceLogPublisher` 推送结构化日志
- **消息格式**：JSON，包含时间戳、模型名、阶段、级别、耗时、异常信息等

示例：

```javascript
// 前端订阅模型推理日志
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
  stompClient.subscribe('/topic/logs/sample-model', (msg) => {
    const log = JSON.parse(msg.body);
    console.log(`[${log.level}] ${log.model} - ${log.phase} ${log.message} (${log.durationMs}ms)`);
  });
});
```
