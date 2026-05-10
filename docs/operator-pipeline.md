# 算子编排系统设计

当前内置通用前后处理器（`DefaultPreprocessor` / `DefaultPostprocessor`）能覆盖"float/int 数组 → tensor"
这类标准转换场景，但对于归一化、分类映射、标签解码等常见操作，仍需编写自定义 jar。

计划设计一套**算子编排系统**作为通用处理器的升级替代：

- **算子注册**：内置数值类算子，每个算子有明确的输入输出语义，与模型类型无关（传统ML / 神经网络 / Transformer / LLM 均适用）
- **声明式编排**：在 model.yml 中声明前后处理流水线，用算子名 + 参数编排 DAG，无需编写 Java 代码
- **可扩展**：支持通过 SPI 注册自定义算子，与内置算子混合编排

## 核心设计

### 算子接口

```java
public interface Operator {
    String name();
    Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params);
}
```

算子间通过 `Map<String, Object>` 传递数据（"黑板"模式），每个算子从上下文中读取所需字段、写入输出字段。

### 数据流

- **Preprocess 入口**：原始请求 `byte[]` 放入上下文 `{"_raw": byte[], "_params": params}`
- **Preprocess 出口**：从上下文提取所有 `OnnxTensor` 条目作为模型输入
- **Postprocess 入口**：所有 `OnnxTensor` 通过 `tensor.getValue()` 转为 Java 数组放入上下文
- **Postprocess 出口**：最终上下文直接作为 JSON 结果返回

### parse_json 与 extract_field 语义

`parse_json` 将 JSON **顶层 key 展平写入上下文**，每个顶层字段直接可用，无需显式提取：

```json
{"dense_features": [0.3, 0.7], "sparse_features": [1, 0]}
```

→ 上下文变为 `{dense_features: [0.3, 0.7], sparse_features: [1, 0]}`

`extract_field` 在两种场景下使用：

| 场景       | 用途                      | 示例                                                                      |
|----------|-------------------------|-------------------------------------------------------------------------|
| 嵌套路径提取   | 访问非顶层的嵌套字段，支持点号路径       | `field: payload.features` 提取 `{payload: {features: [...]}}` 中的 features |
| DAG 分支拆分 | 在 DAG 中声明分支起点，显式标注数据流分叉 | DAG 示例中 `extract_field` + `inputs: [parse]` 声明两个并行分支                    |

线性场景下，顶层字段展平后直接可用，不需要 `extract_field`；DAG 场景下，用 `extract_field` 声明分支拆分点，使 `inputs`
依赖关系与数据流一致。

### 线性 vs DAG

线性流水线是 DAG 的退化特例，统一处理：

- 无 `id` 的算子 → 自动生成 `id`（`step_0`, `step_1`...），自动设置 `inputs` 指向前一步
- 有 `id` 的算子 → DAG 模式，按 `inputs` 构建依赖图，拓扑排序后按层并行执行

### 优先级

model.yml 声明算子 > 自定义 JAR SPI > DefaultPreprocessor/DefaultPostprocessor

## 第一阶段内置算子

所有算子操作数值数组，与模型类型无关。

**预处理算子：**

| 算子              | 说明                        | 参数                                                                  |
|-----------------|---------------------------|---------------------------------------------------------------------|
| `parse_json`    | 解析 raw bytes 为 JSON 结构化数据 | 无                                                                   |
| `extract_field` | 从嵌套路径提取字段或作为 DAG 分支拆分点    | `field`（支持点号路径如 `payload.features`）                                 |
| `normalize`     | 数值归一化（z-score / min-max）  | `field`, `method: "standard"\|"minmax"`, `mean`/`std` 或 `min`/`max` |
| `cast`          | 类型转换（double→float 等）      | `field`, `to: "float32"\|"int64"\|"int32"`                          |
| `to_tensor`     | 将数值数据转为 OnnxTensor        | `field`（可选，单输入时省略；name/type/shape 自 ONNX 元数据推断）                                |

**后处理算子：**

| 算子          | 说明           | 参数                               |
|-------------|--------------|----------------------------------|
| `argmax`    | 取最大值索引（分类）   | `field`, `axis`                  |
| `softmax`   | Softmax 概率转换 | `field`, `axis`                  |
| `top_k`     | 取 Top-K 值和索引 | `field`, `k`                     |
| `label_map` | 索引映射为标签字符串   | `field`, `labels: ["a","b","c"]` |
| `threshold` | 二值化阈值分类      | `field`, `value`                 |
| `round`     | 数值四舍五入       | `field`, `decimals`              |

> `extract_field` 同时用于预处理和后处理。线性场景下仅用于嵌套路径提取，DAG 场景下同时作为分支拆分点。

## 线性流水线（简单场景）

算子按声明顺序依次执行，前一个的输出是后一个的输入，适合单链路场景：

```yaml
name: iris-classifier
version: "1.0"

preprocess:
  - op: parse_json
  - op: normalize
    params: { field: float_input, method: minmax, min: [4.3, 2.0, 1.0, 0.1], max: [7.9, 4.4, 6.9, 2.5] }
  - op: to_tensor
    params: { field: float_input }

postprocess:
  - op: argmax
    params: { field: output, axis: 1 }
  - op: label_map
    params: { field: output, labels: ["setosa", "versicolor", "virginica"] }
```

## DAG 编排（非线性场景）

当流水线存在并行分支或多输入汇合时，线性列表无法表达。通过 `id` + `inputs` 声明算子间的依赖关系，框架据此构建 DAG
并拓扑排序执行，无依赖关系的算子并行执行：

```yaml
name: multi-input-model
version: "1.0"

preprocess:
  - id: parse
    op: parse_json
    # 展平写入上下文: {dense_features: [...], sparse_features: [...]}
    # 无 inputs，消费原始请求数据，是入口节点

  - id: dense
    op: extract_field
    params: { field: dense_features }
    inputs: [parse]                    # DAG 分支拆分点：声明 dense 分支起点

  - id: sparse
    op: extract_field
    params: { field: sparse_features }
    inputs: [parse]                    # DAG 分支拆分点：声明 sparse 分支起点

  - id: norm_dense
    op: normalize
    params: { method: standard, mean: [...], std: [...] }
    inputs: [dense]

  - id: tensor_dense
    op: to_tensor
    params: { field: dense_features }
    inputs: [norm_dense]

  - id: tensor_sparse
    op: to_tensor
    params: { field: sparse_features }
    inputs: [sparse]
```

执行拓扑：

```
              parse
             /      \
     dense        sparse       ← 并行分支
       |              |
  norm_dense     (直通)         ← dense 需归一化，sparse 不需要
       |              |
  tensor_dense  tensor_sparse   ← 并行分支
       \              /
    合并为模型输入 ──→ {dense_features, sparse_features}
```

DAG 编排的关键字段：

| 字段       | 说明                          |
|----------|-----------------------------|
| `id`     | 算子的唯一标识，用于被其他算子引用           |
| `op`     | 算子类型名，对应已注册的算子实现            |
| `params` | 算子参数，各算子自定义                 |
| `inputs` | 依赖的上游算子 id 列表，省略则表示消费原始请求数据 |

## 代码结构

```
base/src/main/java/com/kudosol/ai/inference/operator/
├── Operator.java              # 算子接口
├── OperatorRegistry.java      # 算子注册中心（@PostConstruct 自动注册内置算子 + SPI 加载自定义算子）
├── PipelineExecutor.java      # DAG/线性执行引擎（拓扑排序 + 按层并行）
├── PipelinePreprocessor.java  # 实现 Preprocessor，封装预处理管线
├── PipelinePostprocessor.java # 实现 Postprocessor，封装后处理管线
└── builtin/                   # 内置算子实现
    ├── ParseJson.java
    ├── ExtractField.java
    ├── Normalize.java
    ├── Cast.java
    ├── ToTensor.java
    ├── ArgMax.java
    ├── Softmax.java
    ├── TopK.java
    ├── LabelMap.java
    ├── Threshold.java
    └── Round.java
```

## 集成方式

- `PipelinePreprocessor` / `PipelinePostprocessor` 分别实现 `Preprocessor` / `Postprocessor` 接口，对 `InferenceEngine` 和
  `ModelContainer` 完全透明
- `ModelManager.loadModel()` 中检测 model.yml 是否有 `preprocess`/`postprocess` 声明，有则创建管线处理器，否则走现有 SPI
  JAR → DefaultProcessor 逻辑
- `ModelMeta` 新增 `preprocess` / `postprocess` 字段
- `InferenceEngine`、`ModelContainer`、`InferenceController` 无需修改

算子编排系统上线后，自定义 jar 将降级为兜底方案——只有当算子库无法满足需求时才需要编写。

## 后续阶段

- **第二阶段**：图像类算子（`decode_image`、`resize`、`transpose`）
- **第三阶段**：文本类算子（`bert_tokenize`、`pad_sequence`）
