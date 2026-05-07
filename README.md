# ONNX Java Inference

基于 Spring Boot + ONNX Runtime Java 的 ONNX 模型推理服务，支持通过 SPI 接口动态挂载模型的前后处理代码，最终产出分层 Docker 镜像。

## 架构

采用**分层镜像**设计，推理框架与模型解耦：

```
基础镜像 onnx-java-inference-base
  ├── JRE 21 (Alpine)
  ├── ONNX Runtime Java
  └── 推理框架
        ├── Spring Boot HTTP 服务
        ├── 模型加载引擎
        ├── 前后处理 SPI（Preprocessor / Postprocessor 接口）
        └── 统一推理 API

    ↓ 继承

模型镜像（按需组合）
  ├── model-a/             # 可包含一个或多个模型
  │   ├── model.onnx
  │   ├── model.yml
  │   ├── preprocessor/
  │   └── postprocessor/
  └── model-b/
      ├── model.onnx
      ├── model.yml
      ├── preprocessor/
      └── postprocessor/
```

基础镜像稳定少变，模型镜像只需两行 Dockerfile 即可构建。新增模型无需修改基础镜像。

### 多模型加载

框架启动时自动扫描 `/models/` 目录下的所有子目录，每个子目录视为一个独立模型，全部加载到同一个 JVM 中运行：

```
/models/
├── model-a/          # 自动加载
│   ├── model.onnx
│   ├── model.yml
│   ├── preprocessor/
│   └── postprocessor/
├── model-b/          # 自动加载
│   ├── model.onnx
│   ├── model.yml
│   ├── preprocessor/
│   └── postprocessor/
└── model-c/          # 自动加载
    ├── model.onnx
    ├── model.yml
    ├── preprocessor/
    └── postprocessor/
```

每个模型拥有独立的 `OrtSession`、`Preprocessor`、`Postprocessor`，互不影响。通过 URL 中的模型名路由到对应模型：

```bash
curl -X POST http://localhost:8080/infer/model-a --data-binary @input.bin
curl -X POST http://localhost:8080/infer/model-b --data-binary @input.bin
```

### 部署形态：合设与拆分

一个容器里放多少个模型，完全由模型镜像的 Dockerfile 决定，框架本身不限制。根据场景选择：

| | 合设（多模型同一容器） | 拆分（每模型独立容器） |
|---|---|---|
| 资源占用 | 一个 JVM，内存共享，省资源 | N 个模型 = N 个 JVM，内存开销大 |
| 隔离性 | 模型间无隔离，一个崩溃全部受影响 | 完全隔离，互不影响 |
| 扩缩容 | 所有模型同进退，无法单独扩容 | 按模型独立扩缩容 |
| 适合场景 | 模型少、调用量低 | 模型多、并发高、需要独立伸缩 |

**合设镜像**：把多个模型打包到一个镜像中

```dockerfile
FROM onnx-java-inference-base:1.0.0

COPY model-a/ /models/model-a/
COPY model-b/ /models/model-b/
COPY model-c/ /models/model-c/
```

**拆分镜像**：每个模型一个镜像，独立部署和扩容

```dockerfile
FROM onnx-java-inference-base:1.0.0
COPY heavy-model/ /models/heavy-model/
```

两种方式不需要改代码，只在构建层面决定。也可以混合使用：轻量模型合设，高并发模型单独拆出。

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
│       │   └── Postprocessor.java       # 后处理 SPI 接口
│       ├── engine/
│       │   ├── ModelManager.java        # 模型生命周期管理
│       │   ├── ModelClassLoader.java    # 动态加载前后处理 jar
│       │   ├── ModelContainer.java      # 单模型运行时容器
│       │   └── InferenceEngine.java     # 推理执行引擎
│       ├── controller/
│       │   └── InferenceController.java # REST API
│       └── config/
│           └── InferenceProperties.java
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

```bash
# 对 sample-model 执行推理（输入为 float 数组的二进制数据）
curl -X POST http://localhost:8080/infer/sample-model \
  -H "Content-Type: application/octet-stream" \
  --data-binary "[1.0, 2.0, 3.0, 4.0]"

# 列出已加载模型
curl http://localhost:8080/models

# 查看模型详情
curl http://localhost:8080/models/sample-model
```

## SPI 接口

每个模型通过实现 `Preprocessor` 和 `Postprocessor` 接口定义自己的前后处理逻辑，打包为独立 jar，运行时由框架通过 ServiceLoader 动态加载。

### SPI 安全性

当前方案中，SPI 加载的 jar 与框架运行在同一个 JVM 中，拥有相同权限。安全边界建立在 **Docker 镜像构建流程** 上：前后处理 jar 是在构建模型镜像时由你手动 COPY 进去的，如果攻击者能向镜像中注入恶意 jar，说明他已经能修改 Dockerfile，此时直接篡改基础镜像危害更大。因此容器镜像的构建流程本身就是信任边界，在内部使用场景下 SPI 加载是安全的。

如果未来需要开放模型接入（如第三方提交模型），可通过以下方式增强：

- **代码签名验证**：构建时用私钥签名 jar，框架加载前用公钥验签，只加载签名合法的 jar
- **独立进程隔离**：将推理放到子进程中，通过 gRPC 通信，子进程用 cgroup 限制资源
- **ClassLoader 白名单**：自定义 ClassLoader 拦截 `Runtime`、`ProcessBuilder` 等危险类的加载

### Preprocessor

```java
public interface Preprocessor {
    /**
     * 将原始请求数据转为 ONNX 模型输入张量。
     *
     * @param inputData 原始请求体字节
     * @param params    请求参数（URL query parameters）
     * @return 模型输入名到 OnnxTensor 的映射，key 必须与 model.yml 中的 inputs.name 对应
     */
    Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params);
}
```

### Postprocessor

```java
public interface Postprocessor {
    /**
     * 将 ONNX 模型输出张量转为可 JSON 序列化的结果。
     *
     * @param output 模型输出名到 OnnxTensor 的映射
     * @return 可 JSON 序列化的结果 Map
     */
    Map<String, Object> process(Map<String, OnnxTensor> output);
}
```

## 新增模型

以添加一个名为 `my-model` 的模型为例：

### 1. 准备模型目录

```
my-model/
├── model.onnx
├── model.yml
├── preprocessor/
│   └── preprocessor.jar
└── postprocessor/
    └── postprocessor.jar
```

### 2. 编写 model.yml

```yaml
name: my-model
version: "1.0"
description: 我的模型
inputs:
  - name: input          # 必须与 Preprocessor 返回的 Map key 一致
    type: float32
    shape: [1, 3, 224, 224]
outputs:
  - name: output         # 必须与模型实际输出名一致
    type: float32
    shape: [1, 1000]
```

### 3. 实现 Preprocessor

```java
package com.example.myModel;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import com.kudosol.ai.inference.spi.Preprocessor;
import java.nio.FloatBuffer;
import java.util.Map;

public class MyPreprocessor implements Preprocessor {

    @Override
    public Map<String, OnnxTensor> process(byte[] inputData, Map<String, Object> params) {
        // 实现你的前处理逻辑
        // 例如：解析图像 → resize → normalize → 构造 OnnxTensor
        float[] values = ...; // 你的预处理结果
        long[] shape = {1, 3, 224, 224};
        OnnxTensor tensor = OnnxTensor.createTensor(
                OrtEnvironment.getEnvironment(), FloatBuffer.wrap(values), shape);
        return Map.of("input", tensor);
    }
}
```

### 4. 实现 Postprocessor

```java
package com.example.myModel;

import ai.onnxruntime.OnnxTensor;
import com.kudosol.ai.inference.spi.Postprocessor;
import java.util.Map;

public class MyPostprocessor implements Postprocessor {

    @Override
    public Map<String, Object> process(Map<String, OnnxTensor> output) {
        // 实现你的后处理逻辑
        // 例如：取 argmax → 映射标签名
        OnnxTensor outputTensor = output.get("output");
        float[][] values = (float[][]) outputTensor.getValue();
        return Map.of("predictions", values);
    }
}
```

### 5. 配置 ServiceLoader

在 jar 的 `META-INF/services/` 目录下创建文件：

**META-INF/services/com.kudosol.ai.inference.spi.Preprocessor**
```
com.example.myModel.MyPreprocessor
```

**META-INF/services/com.kudosol.ai.inference.spi.Postprocessor**
```
com.example.myModel.MyPostprocessor
```

### 6. 编写 Dockerfile

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:1.0.0

COPY model.onnx /models/my-model/model.onnx
COPY model.yml /models/my-model/model.yml
COPY preprocessor.jar /models/my-model/preprocessor/preprocessor.jar
COPY postprocessor.jar /models/my-model/postprocessor/postprocessor.jar
```

### 7. 构建并推送模型镜像

```bash
docker build -t harbor.tianyishuju.com/skyease/my-model:1.0.0 .
docker push harbor.tianyishuju.com/skyease/my-model:1.0.0
```

## 构建与部署

### 前置条件

- JDK 21+
- Maven 3.8+
- Docker
- 已登录 Harbor：`docker login harbor.tianyishuju.com`

### 构建并推送基础镜像

```bash
# 1. 编译推理框架
mvn clean package -pl base -am -DskipTests

# 2. 构建基础 Docker 镜像
docker build -t harbor.tianyishuju.com/skyease/onnx-java-inference-base:1.0.0 ./base

# 3. 推送到 Harbor
docker push harbor.tianyishuju.com/skyease/onnx-java-inference-base:1.0.0
```

### 构建并推送模型镜像

```bash
# 1. 编译示例模型的前后处理 jar
mvn clean package -pl sample-model -am -DskipTests

# 2. 构建模型 Docker 镜像
docker build -t harbor.tianyishuju.com/skyease/onnx-java-inference-sample:1.0.0 ./sample-model

# 3. 推送到 Harbor


```

### 运行

```bash
# 从 Harbor 拉取并启动
docker run -d -p 8080:8080 harbor.tianyishuju.com/skyease/onnx-java-inference-sample:1.0.0

# 自定义模型目录（不使用 Docker 时）
java -jar base/target/onnx-java-inference-base-1.0.0-SNAPSHOT-exec.jar \
  --inference.model-dir=/path/to/models
```

### 环境变量

| 变量 | 默认值 | 说明 |
|------|--------|------|
| `INFERENCE_THREAD_COUNT` | 4 | ONNX Runtime 推理线程数 |
| `SERVER_PORT` | 8080 | HTTP 服务端口 |

## 注意事项

- **输入类型校验**：应用启动时会打印每个模型的输入输出信息（名称、类型、形状），前后处理实现必须与模型定义匹配，否则运行时将报错
- **张量类型**：ONNX 模型通常使用 `float32`，Java 端构建 `OnnxTensor` 时需用 `FloatBuffer` 而非 `DoubleBuffer`，类型不匹配会直接报错
- **多维数组**：Java 端用一维数组 + `shape` 数组表示多维张量，例如 `[1, 3, 224, 224]` 对应长度为 `1*3*224*224` 的一维 float 数组
- **线程安全**：`InferenceSession` 线程安全可复用，前后处理类应设计为无状态
