# custom-step — 自定义步骤 JAR 示例

本示例展示如何为 ONNX 推理框架编写自定义前后处理步骤 JAR。这是一个**独立的 Maven 项目**，不依赖 onnx-java-inference 源码，只需
`onnx-java-inference-base` JAR 即可开发。

## 背景

框架内置了 11 个常用步骤（`parse_json`、`normalize`、`argmax` 等），覆盖大多数场景。当内置步骤不满足需求时（自定义激活函数、特殊数学变换等），可以实现
`Step` 接口编写自定义步骤。

本示例包含两个自定义步骤：

| 步骤              | 类型  | 说明                            |
|-----------------|-----|-------------------------------|
| `log_transform` | 前处理 | 对数变换 log(1 + x)，用于偏态分布特征平滑    |
| `sigmoid`       | 后处理 | Sigmoid 激活，将输出映射到 [0, 1] 概率区间 |

## 项目结构

```
custom-step/
├── pom.xml
└── src/main/
    ├── java/com/kudosol/ai/inference/sample/step/
    │   ├── LogTransformStep.java
    │   └── SigmoidStep.java
    └── resources/META-INF/services/
        └── com.kudosol.ai.inference.step.Step
```

## 完整工作流

自定义步骤的开发和使用分为**两个独立阶段**：

1. **开发阶段**：编写 Step 实现，构建 JAR（本项目）
2. **部署阶段**：拿到 JAR 后，与模型文件一起构建部署镜像（参见 `examples/sample-custom-step/`）

### 1. 创建 Maven 项目

创建独立的 `pom.xml`，依赖 `onnx-java-inference-base`，scope 必须为 `provided`（运行时由框架提供，不会打包进 JAR）：

```xml
<groupId>com.example</groupId>
<artifactId>custom-step</artifactId>
<version>1.0.0</version>
<packaging>jar</packaging>

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

**要点**：

- 不需要 parent，不需要 onnx-java-inference 源码，只需 `onnx-java-inference-base` JAR 在 Maven 仓库中可用
- `provided` scope 确保编译时有 API，但不会把框架依赖打进步骤 JAR
- 如果 `onnx-java-inference-base` 尚未发布到远程仓库，需先 `mvn install -pl base` 安装到本地仓库

### 2. 实现 Step 接口

实现 `com.kudosol.ai.inference.step.Step` 接口，定义两个方法：

- `name()` — 返回步骤名称，对应 `model.yml` 中的 `step:` 字段
- `execute(input, params)` — 执行步骤逻辑，从共享上下文读取数据，返回结果写回上下文

**前处理步骤示例** — `LogTransformStep`：

```java
public class LogTransformStep implements Step {

    @Override
    public String name() {
        return "log_transform";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        // StepContextSupport.resolveInputField 自动解析字段名：
        //   - params 中有 field 则用 field
        //   - 模型只有一个输入时自动使用该输入名
        //   - 多输入时未指定 field 会抛异常
        String field = StepContextSupport.resolveInputField(input, params, name());

        Object value = input.get(field);
        if (value == null) throw new IllegalArgumentException("字段 " + field + " 不存在");

        double[] data = ArrayUtils.flattenToDouble(value);
        for (int i = 0; i < data.length; i++) {
            data[i] = Math.log1p(data[i]);
        }

        Map<String, Object> result = new HashMap<>();
        result.put(field, data);
        return result;
    }
}
```

**后处理步骤示例** — `SigmoidStep`：

```java
public class SigmoidStep implements Step {

    @Override
    public String name() {
        return "sigmoid";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params) {
        // 后处理用 resolveOutputField，逻辑同 resolveInputField
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

**关键点：**

- `StepContextSupport.resolveInputField()` / `resolveOutputField()` 自动解析字段名，单输入/输出模型可省略 `field` 参数
- `ArrayUtils.flattenToDouble()` 统一处理各种数值数组类型（`float[]`、`int[]`、`List<?>` 等）
- `execute()` 返回的 Map 会被合并回共享上下文，后续步骤可直接读取

### 3. 配置 SPI 声明

创建 `src/main/resources/META-INF/services/com.kudosol.ai.inference.step.Step`，每行一个实现类全限定名：

```
com.kudosol.ai.inference.sample.step.LogTransformStep
com.kudosol.ai.inference.sample.step.SigmoidStep
```

框架通过 `ServiceLoader` 在模型加载时自动发现这些实现。

### 4. 构建 JAR

```bash
mvn clean package -DskipTests
```

构建产物为 `target/custom-step-1.0.0.jar`。

验证 JAR 内容：

```bash
jar tf target/custom-step-1.0.0.jar | grep -E "(Step|services)"
# META-INF/services/com.kudosol.ai.inference.step.Step
# com/kudosol/ai/inference/sample/step/LogTransformStep.class
# com/kudosol/ai/inference/sample/step/SigmoidStep.class
```

### 5. 部署模型

拿到步骤 JAR 后，有两种部署方式。无论哪种，步骤 JAR 放到模型的 `steps/` 目录下，框架都会自动发现。一个 JAR 可被多个模型复用，只需放到各自模型的
`steps/` 目录下。

#### 方式一：动态拉取（推荐）

不需要为每个模型构建 Docker 镜像，直接复用基础镜像，启动时动态拉取模型包。

打包模型压缩包，包含模型文件和步骤 JAR：

```
my-model.tar.gz
└── my-model/
    ├── model.onnx
    ├── model.yml
    └── steps/
        └── custom-step-1.0.0.jar
```

上传到 S3 或 HTTP 服务器，然后启动：

```bash
# S3 拉取
docker run -d -p 8080:8080 \
  -e MODEL_SOURCES=s3://models/my-model.tar.gz \
  -e S3_ENABLED=true \
  -e S3_ENDPOINT=https://s3.amazonaws.com \
  -e S3_BUCKET=my-bucket \
  -e S3_ACCESS_KEY=xxx \
  -e S3_SECRET_KEY=xxx \
  harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest

# HTTP 拉取
docker run -d -p 8080:8080 \
  -e MODEL_SOURCES=https://example.com/models/my-model.tar.gz \
  harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest
```

框架解压时会保留完整目录结构，`steps/` 下的 JAR 会被 `ModelClassLoader` 自动发现并加载。

**优势**：无需构建模型镜像，模型更新只需重新上传压缩包，重启容器即可生效。

#### 方式二：镜像打包

将模型文件和步骤 JAR 打包进 Docker 镜像，适合 S3/HTTP 不可用或需要固化版本的场景。完整示例参见
`examples/sample-custom-step/`。

```dockerfile
FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:latest

COPY model.onnx /models/<模型名>/model.onnx
COPY model.yml /models/<模型名>/model.yml
COPY custom-step-*.jar /models/<模型名>/steps/
```

### 6. 测试推理

```bash
# 对输入做 log_transform → to_tensor → 推理 → sigmoid
curl -X POST http://localhost:8080/infer/sample-custom-step \
  -H "Content-Type: application/octet-stream" \
  -d '{"float_input": [1.0, 2.0, 3.0, 4.0]}'
```

## 加载机制说明

自定义步骤的 JAR 放在模型目录的 `steps/` 子目录下，框架加载模型时：

1. `ModelClassLoader.loadSteps()` 扫描 `<model-dir>/steps/*.jar`
2. 创建子 `URLClassLoader`，通过 `ServiceLoader.load(Step.class, classLoader)` 发现所有 `Step` 实现
3. `StepRegistry.withSteps()` 创建派生注册表，模型自定义步骤覆盖同名内置步骤
4. 自定义步骤**仅对该模型可见**，不影响其他模型

也可以将自定义步骤 JAR 直接放到应用 classpath 上（全局 SPI），此时所有模型都可以使用。

## 可复用的工具类

| 工具类                  | 方法                                          | 用途                                    |
|----------------------|---------------------------------------------|---------------------------------------|
| `StepContextSupport` | `resolveInputField(ctx, params, stepName)`  | 前处理字段名解析（优先 params.field，否则取模型唯一输入名）  |
| `StepContextSupport` | `resolveOutputField(ctx, params, stepName)` | 后处理字段名解析（优先 params.field，否则取模型唯一输出名）  |
| `StepContextSupport` | `meta(ctx)`                                 | 从上下文获取 `ModelMeta`（输入输出元数据）           |
| `ArrayUtils`         | `flattenToDouble(value)`                    | 将各种数值类型统一转为 `double[]`（支持 1D/2D、List） |
| `ArrayUtils`         | `flattenToFloat(value)`                     | 同上，转 `float[]`                        |
| `ArrayUtils`         | `flattenToLong(value)`                      | 同上，转 `long[]`                         |
| `ArrayUtils`         | `inferShape(value)`                         | 推断数组维度 shape                          |
