# ONNX Java Inference

基于 Spring Boot + ONNX Runtime Java 的 ONNX 模型推理服务，支持声明式算子编排前后处理、SPI 接口动态挂载自定义处理器、S3/HTTP
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
        ├── 算子编排系统（声明式 DAG，11 个内置算子）
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

模型加载时，前后处理器按以下优先级解析：

1. **model.yml pipeline**：在 `model.yml` 中声明 `preprocess` / `postprocess` 算子编排步骤，框架自动构建
   PipelinePreprocessor / PipelinePostprocessor
2. **SPI JAR**：模型目录下存在 `preprocessor/` 或 `postprocessor/` 子目录且包含 jar 时，通过 ServiceLoader 加载自定义实现
3. **自动生成 pipeline**：根据 `model.yml` 的 inputs 定义自动生成 `parse_json` → `to_tensor` 的前处理 pipeline，输出直接透传

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
│   ├── preprocessor/      # 可选，SPI JAR 优先于自动生成
│   └── postprocessor/
└── model-c/          # 自动加载
    ├── model.onnx
    └── model.yml
```

通过 URL 中的模型名路由到对应模型：

```bash
curl -X POST http://localhost:8080/infer/model-a \
  -H "Content-Type: application/octet-stream" \
  -d '{"float_input": [1.0, 2.0, 3.0, 4.0]}'
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
│       │   └── InferenceExceptionHandler.java # 全局异常处理
│       ├── host/
│       │   ├── HostNetworkService.java      # 宿主机 /proc/net 解析
│       │   └── ListeningPort.java           # 监听端口记录
│       ├── log/
│       │   ├── InferenceLog.java            # 推理日志记录
│       │   └── InferenceLogPublisher.java   # WebSocket 日志推送
│       ├── operator/
│       │   ├── Operator.java                # 算子接口
│       │   ├── OperatorRegistry.java        # 算子注册中心
│       │   ├── PipelineExecutor.java        # DAG 拓扑排序 & 并行执行
│       │   ├── PipelinePreprocessor.java    # 算子编排前处理器
│       │   ├── PipelinePostprocessor.java   # 算子编排后处理器
│       │   ├── ArrayUtils.java              # 数组类型转换工具
│       │   └── builtin/                     # 11 个内置算子
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
│           └── PipelineStep.java            # 算子编排步骤定义
└── sample-model/                            # 示例模型
    ├── model.onnx
    └── model.yml
```

## API

| 方法   | 路径                   | 说明                                              |
|------|----------------------|-------------------------------------------------|
| POST | `/infer/{modelName}` | 执行推理，请求体为 `application/octet-stream`，返回 JSON 结果 |
| GET  | `/models`            | 列出所有已加载模型                                       |
| GET  | `/models/{name}`     | 查看模型详情（输入输出定义）                                  |
| GET  | `/host/ports`        | 查询宿主机监听端口列表（需挂载 `/proc`）                        |
| GET  | `/actuator/health`   | 健康检查                                            |

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

# 列出已加载模型
curl http://localhost:8080/models

# 查看模型详情
curl http://localhost:8080/models/sample-model

# 查询宿主机监听端口
curl http://localhost:8080/host/ports
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

## 算子编排系统

声明式 DAG 算子编排，在 `model.yml` 中定义前后处理步骤，无需编写 Java
代码。详见 [docs/operator-pipeline.md](docs/operator-pipeline.md)。

### 内置算子

| 算子名             | 说明                                 |
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
inputs:
  - name: float_input
    type: float32
    shape: [-1, 4]
outputs:
  - name: variable
    type: float32
    shape: [-1, 1]
preprocess:
  - op: parse_json
  - op: to_tensor
    params:
      field: float_input
      name: float_input
      type: float32
      shape: [-1, 4]
postprocess:
  - op: softmax
    params:
      field: variable
  - op: argmax
    params:
      field: variable
  - op: label_map
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

### 方式一：使用算子编排（推荐）

在 `model.yml` 中声明前后处理步骤，无需编写 Java 代码：

```
my-model/
├── model.onnx
└── model.yml
```

```yaml
name: my-model
version: "1.0"
inputs:
  - name: input
    type: float32
    shape: [-1, 4]
outputs:
  - name: output
    type: float32
preprocess:
  - op: parse_json
  - op: to_tensor
    params:
      field: input
      name: input
      type: float32
      shape: [-1, 4]
```

部署：

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
```

### 方式二：使用 SPI JAR 自定义前后处理器

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
    ├── preprocessor/          ← 可选
    │   └── preprocessor.jar
    └── postprocessor/         ← 可选
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

| 变量                       | 默认值        | 说明                                   |
|--------------------------|------------|--------------------------------------|
| `INFERENCE_THREAD_COUNT` | 4          | ONNX Runtime 推理线程数                   |
| `MODEL_SOURCES`          | -          | 模型包来源列表（`s3://` 或 `http(s)://`），逗号分隔 |
| `HOST_PROC_PATH`         | /host/proc | 宿主机 /proc 挂载路径，用于监听端口查询              |
| `S3_ENABLED`             | false      | 是否启用 S3 模型下载                         |
| `S3_ENDPOINT`            | -          | S3 兼容端点（MinIO 等需设置）                  |
| `S3_REGION`              | us-east-1  | S3 Region                            |
| `S3_BUCKET`              | -          | S3 Bucket 名称                         |
| `S3_ACCESS_KEY`          | -          | S3 Access Key                        |
| `S3_SECRET_KEY`          | -          | S3 Secret Key                        |
| `S3_PATH_STYLE_ACCESS`   | false      | 是否使用 Path Style 访问（MinIO 需设为 true）   |

## 注意事项

- **请求 Content-Type**：推理接口 `/infer/{modelName}` 接收 `application/octet-stream`，请求体为原始字节，由 `parse_json`
  算子或自定义 Preprocessor 解析
- **输入类型校验**：应用启动时会打印每个模型的输入输出信息（名称、类型、形状），前后处理实现必须与模型定义匹配，否则运行时将报错
- **张量类型**：ONNX 模型通常使用 `float32`，Java 端构建 `OnnxTensor` 时需用 `FloatBuffer` 而非 `DoubleBuffer`，类型不匹配会直接报错
- **多维数组**：Java 端用一维数组 + `shape` 数组表示多维张量，例如 `[1, 3, 224, 224]` 对应长度为 `1*3*224*224` 的一维 float 数组
- **线程安全**：`OrtSession` 线程安全可复用，前后处理类应设计为无状态
- **S3 兼容性**：S3 下载功能兼容 AWS S3 和 MinIO 等 S3 兼容存储，使用 MinIO 时需设置 `S3_ENDPOINT` 和
  `S3_PATH_STYLE_ACCESS=true`
- **宿主机端口查询**：容器内读取 `/proc/net` 时会获取容器自身网络栈，需读取 `/host/proc/1/net/` 才能获取宿主机网络信息，框架已自动处理此逻辑
