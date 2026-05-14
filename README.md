# ONNX Java Inference

基于 Spring Boot + ONNX Runtime Java 的 ONNX 模型推理服务，支持声明式步骤编排前后处理、SPI 接口动态挂载自定义处理器、S3/HTTP
模型包动态加载，以及宿主机监听端口查询。

## 架构

采用**基础镜像 + 模型包**设计，推理框架与模型解耦：

```
基础镜像 onnx-java-inference-base
  ├── JRE 21
  ├── ONNX Runtime Java
  └── 推理框架
        ├── Spring Boot HTTP 服务
        ├── 模型源下载器（S3 / HTTP）
        ├── 模型加载引擎
        ├── 步骤编排系统（声明式 DAG，11 个内置步骤）
        ├── 前后处理 SPI（Preprocessor / Postprocessor 接口）
        ├── 推理日志 WebSocket 推送
        ├── 宿主机监听端口查询
        └── 统一推理 API

    ↓ 两种模型加载方式

方式一：模型打包进镜像          方式二：动态拉取
  ├── model-a/                   启动时从 S3 或 HTTP 下载模型包
  │   ├── model.onnx             → 解压到 /models/
  │   ├── model.yml              → 自动加载
  │   ├── preprocessor/ (可选)
  │   └── postprocessor/ (可选)
  └── model-b/ ...
```

### 模型加载流程

```
Spring Boot 启动
    ↓
ModelSourceDownloader (@Order(1))
    - 遍历 inference.model-sources 列表
    - s3:// URI → 通过 S3Client 下载 tar.gz → 解压到 /models/
    - http(s):// URI → 通过 HTTP 下载 → 解压到 /models/
    ↓
ModelManager (@Order(2))
    - 扫描 /models/ 下的子目录
    - 对每个子目录：
        1. 解析 model.yml → ModelMeta
        2. 加载 model.onnx → OrtSession
        3. 解析前后处理器（优先级：model.yml pipeline > SPI JAR > 自动生成 pipeline）
        4. 调用 init(ModelMeta) 传入模型元数据
        5. 封装为 ModelContainer
```

### 前后处理器解析优先级

模型加载时，**前处理和后处理分别独立解析**，各自按以下优先级选取（互斥，不会合并）：

1. **model.yml pipeline**：在 `model.yml` 中声明 `preprocess` / `postprocess` 步骤管线，框架自动构建
   PipelinePreprocessor / PipelinePostprocessor。管线中可以自由混用内置步骤和自定义步骤
2. **SPI JAR**：模型目录下存在 `preprocessor/` 或 `postprocessor/` 子目录且包含 jar 时，通过 ServiceLoader 加载自定义实现
3. **自动生成 pipeline**：前处理根据 ONNX 模型元数据自动生成 `parse_json` → `to_tensor` 管线；后处理使用空管线（直接返回
   `tensor.getValue()`）

**关于共存**：如果模型目录同时存在 `model.yml` 声明了 pipeline、`preprocessor/` 或 `postprocessor/` 下的 SPI JAR、以及
`steps/` 下的自定义步骤 JAR，行为如下：

- `steps/` 下的自定义步骤**总是被加载**到 StepRegistry，只要 `model.yml` 管线中引用了就能使用
- `preprocessor/` 或 `postprocessor/` 下的 SPI JAR **被忽略**，因为 model.yml 声明了 pipeline 就走管线，不会降级到 SPI
- 因此应避免同时放置 SPI JAR 和 model.yml pipeline 声明，以免造成困惑

### 三种扩展方式对比

| 扩展方式              | 粒度   | 需要写 Java | 适用场景                                  | 能否与内置步骤混用 |
|-------------------|------|----------|---------------------------------------|-----------|
| model.yml 内置步骤    | 单步骤  | 否        | 常见前后处理（JSON 解析、归一化、分类等）               | —         |
| model.yml + 自定义步骤 | 单步骤  | 是        | 内置步骤不满足需求（自定义激活函数、特殊数学变换等）            | 可以        |
| SPI JAR 前后处理器     | 整体替换 | 是        | 完全自定义前后处理逻辑（如图像 resize、文本 tokenize 等） | 不可以       |

**自定义步骤 vs SPI 前后处理器**：两者解决不同层面的问题。自定义步骤操作黑板上下文中的 Java 对象（数值数组），适合逐步骤组合；SPI
前后处理器直接构造/读取 `OnnxTensor`，适合需要特殊数据格式（`BufferedImage`、token 序列等）的整体处理场景，难以拆分为独立步骤。

### 多模型加载

框架启动时自动扫描 `/models/` 目录下的所有子目录，每个子目录视为一个独立模型：

```
/models/
├── model-a/          # 自动加载
│   ├── model.onnx
│   └── model.yml          # 无 pipeline/JAR，自动生成处理器
├── model-b/          # 自动加载
│   ├── model.onnx
│   ├── model.yml          # 含 preprocess/postprocess pipeline 定义
│   ├── steps/         # 可选，自定义步骤 JAR（仅对该模型可见）
│   │   └── custom-step.jar
│   ├── preprocessor/      # 可选，SPI JAR 优先于自动生成
│   └── postprocessor/
└── model-c/          # 自动加载
    ├── model.onnx
    └── model.yml
```

### 部署形态

| 部署方式      | 说明              | 适合场景           |
|-----------|-----------------|----------------|
| 镜像打包      | 模型文件 COPY 进镜像   | 本地开发、无 S3 环境   |
| S3 动态拉取   | 启动时从 S3 下载模型包   | 生产环境、CI/CD 自动化 |
| HTTP 动态拉取 | 启动时从 HTTP 下载模型包 | 临时分享、公网模型包     |
| 合设        | 多模型同一容器         | 模型少、调用量低       |
| 拆分        | 每模型独立容器         | 模型多、并发高、需独立伸缩  |

## 项目结构

```
onnx-java-inference/
├── pom.xml                                  # 根 POM
├── base/                                    # 基础推理框架
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/main/java/.../inference/
│       ├── InferenceApplication.java        # 启动入口
│       ├── config/
│       │   ├── InferenceProperties.java     # inference.* 配置
│       │   ├── S3Properties.java            # inference.s3.* 配置
│       │   ├── HostProcProperties.java      # host-proc.* 配置
│       │   ├── ApiKeyFilter.java            # API Key 认证过滤器
│       │   └── WebSocketConfig.java         # STOMP WebSocket 配置
│       ├── controller/
│       │   ├── InferenceController.java     # 推理 & 模型 API
│       │   └── HostNetworkController.java   # 宿主机端口查询 API
│       ├── engine/
│       │   ├── InferenceEngine.java         # 推理执行引擎
│       │   ├── ModelManager.java            # 模型生命周期管理
│       │   ├── ModelContainer.java          # 单模型运行时容器
│       │   └── ModelClassLoader.java        # 动态加载前后处理 JAR
│       ├── exception/
│       │   ├── InferenceExceptionHandler.java # 全局异常处理
│       │   ├── BusinessException.java         # 业务异常基类
│       │   ├── BadRequestException.java       # 400
│       │   ├── UnauthorizedException.java     # 401
│       │   ├── ForbiddenException.java        # 403
│       │   ├── NotFoundException.java         # 404
│       │   ├── PayloadTooLargeException.java  # 413
│       │   ├── TooManyRequestException.java   # 429
│       │   └── RequestTimeoutException.java   # 503
│       ├── host/
│       │   ├── HostNetworkService.java      # 宿主机 /proc/net 解析
│       │   └── ListeningPort.java           # 监听端口记录
│       ├── log/
│       │   ├── InferenceLog.java            # 推理日志记录
│       │   └── InferenceLogPublisher.java   # WebSocket 日志推送
│       ├── step/
│       │   ├── Step.java                    # 步骤接口
│       │   ├── StepRegistry.java            # 步骤注册中心
│       │   ├── StepContextSupport.java      # 字段解析工具
│       │   ├── PipelineExecutor.java        # DAG 拓扑排序 & 并行执行
│       │   ├── PipelinePreprocessor.java    # 步骤编排前处理器
│       │   ├── PipelinePostprocessor.java   # 步骤编排后处理器
│       │   ├── ArrayUtils.java              # 数组类型转换工具
│       │   └── builtin/                     # 11 个内置步骤
│       │       ├── ParseJson.java           # parse_json
│       │       ├── ExtractField.java        # extract_field
│       │       ├── Normalize.java           # normalize
│       │       ├── Cast.java                # cast
│       │       ├── ToTensor.java            # to_tensor
│       │       ├── ArgMax.java              # argmax
│       │       ├── Softmax.java             # softmax
│       │       ├── TopK.java                # top_k
│       │       ├── LabelMap.java            # label_map
│       │       ├── Threshold.java           # threshold
│       │       └── Round.java               # round
│       ├── source/
│       │   └── ModelSourceDownloader.java   # S3/HTTP 模型源下载器
│       └── spi/
│           ├── Preprocessor.java            # 前处理 SPI 接口
│           ├── Postprocessor.java           # 后处理 SPI 接口
│           ├── ModelMeta.java               # 模型元数据
│           ├── TensorMeta.java              # 张量元数据
│           └── PipelineStep.java            # 步骤编排步骤定义
└── examples/                                # 示例
    ├── sample-model/                        # 零 Java 代码示例（纯 YAML 步骤编排）
    │   ├── model.onnx
    │   └── model.yml
    ├── sample-multi-model/                  # 多输入/输出示例
    │   ├── bidaf-9.onnx
    │   └── model.yml
    ├── custom-step/                         # 自定义步骤 JAR 项目（独立 Maven 项目）
    │   ├── pom.xml
    │   └── src/main/java/.../sample/step/
    │       ├── LogTransformStep.java        # 自定义前处理步骤
    │       └── SigmoidStep.java            # 自定义后处理步骤
    └── sample-custom-step/                  # 使用自定义步骤的模型部署示例
        ├── model.onnx
        ├── model.yml
        └── Dockerfile
```

## API

| 方法   | 路径                           | 说明                                              |
|------|------------------------------|-------------------------------------------------|
| POST | `/infer/{modelName}`         | 执行推理，请求体为 `application/octet-stream`，返回 JSON 结果 |
| GET  | `/models`                    | 列出所有已加载模型                                       |
| GET  | `/models/{name}`             | 查看模型详情（输入输出定义）                                  |
| GET  | `/host/ports`                | 查询宿主机监听端口列表（需挂载 `/proc`）                        |
| GET  | `/actuator/health`           | 汇总健康状态（包含所有组件）                                  |
| GET  | `/actuator/health/liveness`  | 进程存活状态（K8s livenessProbe）                       |
| GET  | `/actuator/health/readiness` | 服务就绪状态（含模型加载检查，K8s readinessProbe）              |

### API Key 认证

通过 `model.yml` 中的 `api-keys` 字段为模型配置访问密钥。声明了 `api-keys` 的模型要求请求携带 `X-API-Key` 头且 Key
在列表中，未声明的模型开放访问。

```yaml
name: my-model
version: "1.0"
api-keys:
  - sk-67efb7ea8abfc5fcadd5177b85d297e77d4823f41daba027
preprocess:
  - step: parse_json
  - step: to_tensor
    params: { field: input }
```

```bash
# 无 Key 访问受保护模型 → 401
curl http://localhost:8080/models/my-model
# → {"code":401,"data":null,"error":"访问模型 'my-model' 需要 API Key"}

# 错误 Key 访问 → 403
curl -H "X-API-Key: sk-wrong" http://localhost:8080/models/my-model
# → {"code":403,"data":null,"error":"API Key 无权访问模型: my-model"}

# 正确 Key 访问 → 200
curl -H "X-API-Key: sk-67efb7ea8abfc5fcadd5177b85d297e77d4823f41daba027" http://localhost:8080/models/my-model

# 未配置 api-keys 的模型 → 开放访问
curl http://localhost:8080/models/open-model
```

### 推理请求示例

```bash
# 单条推理
curl -X POST http://localhost:8080/infer/sample-model \
  -H "Content-Type: application/octet-stream" \
  -d '{"float_input": [1.0, 2.0, 3.0, 4.0]}'

# 批量推理
curl -X POST http://localhost:8080/infer/sample-model \
  -H "Content-Type: application/octet-stream" \
  -d '{"float_input": [[1.0, 2.0, 3.0, 4.0], [5.0, 6.0, 7.0, 8.0]]}'

# 使用自定义步骤的模型推理（管线: parse_json → log_transform → to_tensor → 推理 → sigmoid）
curl -X POST http://localhost:8080/infer/sample-custom-step \
  -H "Content-Type: application/octet-stream" \
  -d '{"float_input": [[1.0, 2.0, 3.0, 4.0]]}'
# → {"variable":[1.0]}    (sigmoid 将原始输出 368.75665 映射到 [0,1] 区间)

# 列出已加载模型
curl http://localhost:8080/models

# 查看模型详情
curl http://localhost:8080/models/sample-model

# 查询宿主机监听端口
curl http://localhost:8080/host/ports
```

### 健康检查

框架通过 Spring Boot Actuator 暴露三个健康检查端点。

#### 汇总状态

```bash
curl http://localhost:8080/actuator/health
```

返回示例（模型已加载）：

```json
{
  "status": "UP",
  "components": {
    "ping": {"status": "UP"},
    "diskSpace": {"status": "UP"},
    "model": {
      "status": "UP",
      "details": {
        "ready": true,
        "modelCount": 2,
        "models": ["sample-model", "load-predict"]
      }
    }
  }
}
```

#### 存活检查（K8s livenessProbe）

```bash
curl http://localhost:8080/actuator/health/liveness
```

返回示例：

```json
{
  "status": "UP",
  "components": {
    "livenessState": {"status": "UP"}
  }
}
```

#### 就绪检查（K8s readinessProbe）

```bash
curl http://localhost:8080/actuator/health/readiness
```

返回示例（模型已加载）：

```json
{
  "status": "UP",
  "components": {
    "readinessState": {"status": "UP"},
    "model": {
      "status": "UP",
      "details": {
        "ready": true,
        "modelCount": 2,
        "models": ["sample-model", "load-predict"]
      }
    }
  }
}
```

返回示例（无模型）：

```json
{
  "status": "DOWN",
  "components": {
    "readinessState": {"status": "UP"},
    "model": {
      "status": "DOWN",
      "details": {
        "ready": false,
        "modelCount": 0,
        "models": []
      }
    }
  }
}
```

K8s 配置示例：

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
```

### 推理日志 WebSocket 推送

通过 **STOMP over WebSocket** 实时推送推理日志：

- 订阅端点：`/ws`（SockJS fallback）
- 订阅主题：
  - `/topic/logs` — 全局推理日志
  - `/topic/logs/{modelName}` — 按模型过滤
  - `/topic/errors` — 仅错误日志

```javascript
const socket = new SockJS('http://localhost:8080/ws');
const stompClient = Stomp.over(socket);

stompClient.connect({}, () => {
  stompClient.subscribe('/topic/logs/sample-model', (msg) => {
    const log = JSON.parse(msg.body);
    console.log(`[${log.level}] ${log.model} - ${log.phase} (${log.durationMs}ms)`);
  });
});
```

## 步骤编排系统

声明式 DAG 步骤编排，在 `model.yml` 中定义前后处理步骤，无需编写 Java
代码。详见 [docs/operator-pipeline.md](docs/operator-pipeline.md)。

### 内置步骤

| 步骤名             | 说明                                 |
|-----------------|------------------------------------|
| `parse_json`    | 解析原始 `byte[]` 为 JSON，展开顶层 key 到上下文 |
| `extract_field` | 按 dot-path 提取嵌套字段                  |
| `normalize`     | Z-score 或 min-max 归一化              |
| `cast`          | 数值类型转换（float32/int64/int32）        |
| `to_tensor`     | 数值数组转 OnnxTensor，自动推断 -1 维度        |
| `argmax`        | 最大值索引                              |
| `softmax`       | 数值稳定 softmax                       |
| `top_k`         | Top-K 值和索引                         |
| `label_map`     | 索引映射为字符串标签                         |
| `threshold`     | 二值化阈值判断                            |
| `round`         | 数值舍入                               |

### model.yml pipeline 示例

```yaml
name: sample-model
version: "1.0"
preprocess:
  - step: parse_json
  - step: to_tensor
    params:
      field: float_input
postprocess:
  - step: softmax
    params:
      field: variable
  - step: argmax
    params:
      field: variable
  - step: label_map
    params:
      field: variable
      labels: [cat, dog]
```

步骤间支持 DAG 依赖（通过 `id` 和 `inputs` 字段），同层步骤并行执行。

## SPI 接口

每个模型可通过实现 `Preprocessor` 和 `Postprocessor` 接口定义自定义前后处理逻辑，打包为独立 jar，运行时由框架通过
ServiceLoader 动态加载。

### Preprocessor

```java
public interface Preprocessor {
    default void init(ModelMeta meta) {}
    Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) throws Exception;
}
```

### Postprocessor

```java
public interface Postprocessor {
    default void init(ModelMeta meta) {}
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
    private List<PipelineStep> preprocess;
    private List<PipelineStep> postprocess;
}
```

### SPI 安全性

SPI 加载的 jar 与框架运行在同一个 JVM 中。安全边界建立在 Docker 镜像构建流程或 S3
上传流程上。如果需要开放第三方模型接入，可通过代码签名验证、独立进程隔离、ClassLoader 白名单等方式增强。

## 新增模型

### 方式一：使用步骤编排（推荐）

在 `model.yml` 中声明前后处理步骤，无需编写 Java 代码：

```
my-model/
├── model.onnx
└── model.yml
```

```yaml
name: my-model
version: "1.0"
preprocess:
  - step: parse_json
  - step: to_tensor
    params:
      field: input
```

部署：

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
```

### 方式二：使用自定义步骤

当内置步骤不满足需求时（如自定义激活函数、特殊数学变换），可实现 `Step` 接口编写自定义步骤，放到模型目录的 `steps/`
下。自定义步骤只对声明它的模型可见，不影响其他模型。

#### 1. 创建 Maven 项目

创建独立的 `pom.xml`，依赖 `onnx-java-inference-base`（`provided` scope），不需要 onnx-java-inference 源码：

```xml
<groupId>com.example</groupId>
<artifactId>my-custom-step</artifactId>
<version>1.0.0</version>

<properties>
    <java.version>21</java.version>
    <maven.compiler.source>21</maven.compiler.source>
    <maven.compiler.target>21</maven.compiler.target>
</properties>

<dependencies>
    <dependency>
        <groupId>com.kudosol.ai</groupId>
        <artifactId>onnx-java-inference-base</artifactId>
        <version>1.0.0-SNAPSHOT</version>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

#### 2. 实现 Step 接口

以 `sigmoid` 步骤为例：

```java
package com.example.myModel.step;

import com.kudosol.ai.inference.step.ArrayUtils;
import com.kudosol.ai.inference.step.Step;
import com.kudosol.ai.inference.step.StepContextSupport;
import java.util.HashMap;
import java.util.Map;

public class SigmoidStep implements Step {

    @Override
    public String name() {
        return "sigmoid";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        String field = StepContextSupport.resolveOutputField(input, params, name());

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        for (int i = 0; i < data.length; i++) {
            data[i] = 1.0 / (1.0 + Math.exp(-data[i]));
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, data);
        return result;
    }
}
```

#### 3. 配置 ServiceLoader

创建 `META-INF/services/com.kudosol.ai.inference.step.Step`：

```
com.example.myModel.step.SigmoidStep
```

#### 4. 在 model.yml 中使用

```yaml
name: my-model
version: "1.0"
preprocess:
  - step: parse_json
  - step: to_tensor
    params: { field: input }
postprocess:
  - step: sigmoid
    params: { field: output }
```

#### 5. 构建与部署

步骤 JAR 的构建和模型部署是独立的两步：

```bash
# 1. 编译自定义步骤 jar
cd my-custom-step && mvn clean package -DskipTests && cd ..

# 2. 将 JAR 复制到模型部署目录，构建模型镜像
cp my-custom-step/target/my-custom-step-*.jar my-model/
docker build -t my-model:latest ./my-model
```

Dockerfile：

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
COPY my-custom-step-*.jar /models/my-model/steps/
```

自定义步骤 jar 放到模型的 `steps/` 目录下，框架通过 ServiceLoader 自动发现。可放置多个 jar，每个 jar 可包含多个 Step 实现。

#### 完整示例

- **步骤 JAR 项目**：`examples/custom-step/` — 独立 Maven 项目，产出步骤
  JAR，详见 [examples/custom-step/README.md](examples/custom-step/README.md)
- **模型部署示例**：`examples/sample-custom-step/` — 拿到 JAR 后构建部署镜像的完整示例

### 方式三：使用 SPI JAR 自定义前后处理器

适用于需要特殊逻辑的模型（如图像 resize/normalize、文本 tokenize 等）。

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

#### 2. 实现 Preprocessor

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
        float[] values = ...;
        long[] shape = {1, featureCount};
        OnnxTensor tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape);
        return Map.of(inputName, tensor);
    }
}
```

#### 3. 实现 Postprocessor

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

#### 4. 配置 ServiceLoader

在 jar 的 `META-INF/services/` 目录下创建文件：

**META-INF/services/com.kudosol.ai.inference.spi.Preprocessor**
```
com.example.myModel.MyPreprocessor
```

**META-INF/services/com.kudosol.ai.inference.spi.Postprocessor**
```
com.example.myModel.MyPostprocessor
```

#### 5. 部署

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
COPY preprocessor.jar /models/my-model/preprocessor/preprocessor.jar
COPY postprocessor.jar /models/my-model/postprocessor/postprocessor.jar
```

## 模型包格式

上传到 S3 或通过 HTTP 分发的 tar.gz 包应包含以模型名命名的顶层目录：

```
my-model.tar.gz
└── my-model/
    ├── model.onnx
    ├── model.yml
    ├── steps/             ← 可选，自定义步骤 JAR
    │   └── custom-step.jar
    ├── preprocessor/          ← 可选，SPI 前处理器 JAR
    │   └── preprocessor.jar
    └── postprocessor/         ← 可选，SPI 后处理器 JAR
        └── postprocessor.jar
```

框架解压时自动识别顶层目录名作为模型名，解压到 `/models/<模型名>/` 下。支持 `.tar.gz`、`.tgz`、`.zip` 格式。

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
docker build -t harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest ./base

# 3. 推送到 Harbor
docker push harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
```

### 构建自定义步骤示例镜像

```bash
# 1. 编译自定义步骤 jar
cd examples/custom-step && mvn clean package -DskipTests && cd ../..

# 2. 将 JAR 复制到模型部署目录
cp examples/custom-step/target/custom-step-*.jar examples/sample-custom-step/

# 3. 构建模型 Docker 镜像
docker build -t harbor.tianyishuju.com/skyease/onnx-java-inference-sample-custom-step:latest ./examples/sample-custom-step

# 4. 推送到 Harbor
docker push harbor.tianyishuju.com/skyease/onnx-java-inference-sample-custom-step:latest
```

### 运行

```bash
# 基础模式（S3/HTTP 动态拉取模型）
docker run -d -p 8080:8080 \
  -e MODEL_SOURCES=http://example.com/models/sample-model.zip \
  harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest

# S3 动态拉取模式
docker run -d -p 8080:8080 \
  -e S3_ENABLED=true \
  -e S3_ENDPOINT=https://s3.amazonaws.com \
  -e S3_BUCKET=my-bucket \
  -e MODEL_SOURCES=s3://models/sample-model.tar.gz \
  -e S3_ACCESS_KEY=xxx \
  -e S3_SECRET_KEY=xxx \
  harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest

# 查询宿主机监听端口（需挂载 /proc）
docker run -d -p 8080:8080 \
  -v /proc:/host/proc:ro \
  harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest

# 本地 jar 运行（自定义模型目录）
java -jar base/target/onnx-java-inference-base-1.0.0-SNAPSHOT-exec.jar \
  --inference.model-dir=/path/to/models
```

### 环境变量

| 变量                          | 默认值        | 说明                                   |
|-----------------------------|------------|--------------------------------------|
| `INFERENCE_THREAD_COUNT`    | 4          | ONNX Runtime 推理线程数                   |
| `MODEL_SOURCES`             | -          | 模型包来源列表（`s3://` 或 `http(s)://`），逗号分隔 |
| `HOST_PROC_PATH`            | /host/proc | 宿主机 /proc 挂载路径，用于监听端口查询              |
| `S3_ENABLED`                | false      | 是否启用 S3 模型下载                         |
| `S3_ENDPOINT`               | -          | S3 兼容端点（MinIO 等需设置）                  |
| `S3_REGION`                 | us-east-1  | S3 Region                            |
| `S3_BUCKET`                 | -          | S3 Bucket 名称                         |
| `S3_ACCESS_KEY`             | -          | S3 Access Key                        |
| `S3_SECRET_KEY`             | -          | S3 Secret Key                        |
| `S3_PATH_STYLE_ACCESS`      | false      | 是否使用 Path Style 访问（MinIO 需设为 true）   |
| `MAX_REQUEST_SIZE`          | 50MB       | HTTP 请求体最大大小                         |
| `MAX_CONCURRENT_INFERENCES` | 4          | 最大并发推理数，超限返回 429                     |
| `SHUTDOWN_TIMEOUT`          | 30s        | 优雅停机等待超时                             |
| `DOWNLOAD_TIMEOUT`          | 5m         | 模型下载超时                               |
| `DOWNLOAD_RETRY_COUNT`      | 2          | 模型下载重试次数                             |
| `DOWNLOAD_RETRY_DELAY`      | 3s         | 模型下载重试初始延迟                           |
| `INFERENCE_TIMEOUT`         | 60s        | 推理超时时间                               |

## 输入校验

采用**三层校验**架构：Controller 手动守卫 → Step 就近校验 → 全局异常兜底。

### 三层职责与边界

| 层 | 位置 | 职责 | 校验内容 | 不做什么 |
|---|---|---|---|---|
| **Controller 守卫** | `InferenceController` | 传输层门禁 | `modelName` 非空/格式白名单、`inputData` 非空/大小 | 不校验业务语义（shape、参数范围） |
| **Step 就近校验** | 各 `Step.execute()` | 业务层语义 | 张量形状一致性、动态维度整除性、参数范围 | 不重复校验 Controller 已守卫的非空/格式 |
| **全局异常兜底** | `InferenceExceptionHandler` | 框架级异常兜底 | 参数类型不匹配、缺少必要参数、请求方法错误、请求体格式错误 | 不补充业务校验逻辑 |

### 约定

1. **Controller 只做传输层校验**——防止非法输入穿透到业务层（如路径穿越的 modelName、空请求体）。这些校验与具体模型无关，对所有接口通用
2. **Step 只做业务语义校验**——每个 Step 知道自己需要什么数据，就近校验最准确。例如 `ToTensor` 校验元素数是否匹配模型 shape，`TopK` 校验 topK 在 1-100 之间
3. **全局异常兜底只处理框架异常**——Spring MVC 抛出的 `MethodArgumentTypeMismatchException`、`MissingServletRequestParameterException` 等，这些不是业务代码能 catch 的
4. **不跨层重复校验**——Controller 已守卫的非空/格式检查，Step 内不作为主防线重复；Step 内的防御性 null 检查（如 `value == null`）属于防御编程，不是主防线
5. **管理类接口如果未来引入 JSON 请求体**，可使用 DTO + `@Valid` 声明式校验，与三层模式并存不冲突

### 模型名称格式白名单

`modelName` 在系统中会被拼进路径：

```
/models/{modelName}/model.onnx    ← 文件系统
s3://bucket/{modelName}.tar.gz    ← S3 路径
```

因此必须防止路径穿越。白名单正则 `[a-zA-Z0-9_.\\-]+` 只允许以下字符：

| 允许的字符 | 示例 |
|---|---|
| 大小写字母 | `a-z` `A-Z` |
| 数字 | `0-9` |
| 下划线 | `_` |
| 点号 | `.` |
| 连字符 | `-` |

合法名称：`iris-v2`、`resnet50`、`my.model_v1`

路径穿越的核心手段是用 `/` 或 `\` 跳出当前目录（如 `../../etc/passwd`），白名单排除了所有路径相关的特殊字符：

| 被排除的字符 | 危险原因 |
|---|---|
| `/` `\` | 路径分隔符，穿越的核心手段 |
| 空格 | 可用于注入额外参数 |
| `%` | URL 编码前缀，防止二次解码绕过 |
| `?` `#` `&` | URL 查询参数注入 |
| `..` | 虽然点号允许，但没有 `/` 配合就无法构成 `../` 跳转 |

本质：`modelName` 只能是一个扁平的文件名，不能包含目录结构。

### 校验示例

**Controller 守卫**（传输层）：

```java
// modelName 格式白名单，防止路径穿越
if (!modelName.matches("[a-zA-Z0-9_.\\-]+")) {
    throw new BadRequestException("模型名称格式非法");
}
// 请求体非空
if (inputData == null || inputData.length == 0) {
    throw new BadRequestException("请求体不能为空");
}
```

**Step 就近校验**（业务层）：

```java
// ToTensor: 元素数必须匹配模型 shape
if (actualCount != expectedCount) {
    throw new BadRequestException(
        "输入 '%s' 期望 %d 个元素 (shape=%s)，实际 %d 个"
            .formatted(field, expectedCount, Arrays.toString(shape), actualCount));
}
// ToTensor: 动态维度必须能整除
if (totalElements % knownProduct != 0) {
    throw new BadRequestException(
        "输入 '%s' 数据量 %d 无法整除已知维度乘积 %d"
            .formatted(field, totalElements, knownProduct));
}
```

**全局异常兜底**：

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(...) {
    return fail(400, "参数类型错误: " + e.getName());
}
```

### 错误响应

所有校验失败统一通过 `ApiResponse` 返回，HTTP 状态码与业务码一致：

| 场景 | HTTP 状态码 | 错误示例 |
|---|---|---|
| 模型名称格式非法 | 400 | `{"code":400,"data":null,"error":"模型名称格式非法"}` |
| 请求体为空 | 400 | `{"code":400,"data":null,"error":"请求体不能为空"}` |
| 张量元素数不匹配 | 400 | `{"code":400,"data":null,"error":"输入 'float_input' 期望 6 个元素 (shape=[2, 3])，实际 5 个"}` |
| 请求体超过大小限制 | 413 | `{"code":413,"data":null,"error":"请求体大小 ... 超过限制 ..."}` |
| API Key 无权访问模型 | 403 | `{"code":403,"data":null,"error":"API Key 无权访问模型: ..."}` |

## 注意事项

- **请求 Content-Type**：推理接口 `/infer/{modelName}` 接收 `application/octet-stream`，请求体为原始字节，由 `parse_json`
  步骤或自定义 Preprocessor 解析
- **输入类型校验**：应用启动时会打印每个模型的输入输出信息（名称、类型、形状），前后处理实现必须与模型定义匹配，否则运行时将报错
- **张量类型**：ONNX 模型通常使用 `float32`，Java 端构建 `OnnxTensor` 时需用 `FloatBuffer` 而非 `DoubleBuffer`，类型不匹配会直接报错
- **多维数组**：Java 端用一维数组 + `shape` 数组表示多维张量，例如 `[1, 3, 224, 224]` 对应长度为 `1*3*224*224` 的一维 float 数组
- **线程安全**：`OrtSession` 线程安全可复用，前后处理类应设计为无状态
- **S3 兼容性**：S3 下载功能兼容 AWS S3 和 MinIO 等 S3 兼容存储，使用 MinIO 时需设置 `S3_ENDPOINT` 和
  `S3_PATH_STYLE_ACCESS=true`
- **宿主机端口查询**：容器内读取 `/proc/net` 时会获取容器自身网络栈，需读取 `/host/proc/1/net/` 才能获取宿主机网络信息，框架已自动处理此逻辑
