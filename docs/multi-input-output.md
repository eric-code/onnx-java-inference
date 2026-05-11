# 多输入 / 多输出场景设计

## 背景

步骤（`to_tensor` / `sigmoid` / `argmax` …）在设计上**每次只处理一个 field**——读 context 里的某个字段，转换后写回
context。所以单输入/单输出场景下，框架可以无歧义地把 `field` 默认成那个唯一名字（参考
`OperatorContextSupport.resolveInputField` / `resolveOutputField`）。

但只要候选名字 ≥2，框架就拒绝默认，必须显式写 `field`。本文档解释为什么这样设计，以及多输入/多输出的写法。

## 为什么多输入必须显式写 field

具体例子：模型有两个输入 `age`、`income`。

请求体：
```json
{
  "age":    [[25], [30]],
  "income": [[50000], [80000]]
}
```

`parse_json` 把顶层 key 展平进 context：
```
{
  "age":     [[25], [30]],
  "income":  [[50000], [80000]],
  "_meta":   <ModelMeta>,
  "_raw":    <byte[]>,
  "_params": {...}
}
```

到了 `to_tensor`，如果不写 `field`，`OperatorContextSupport.resolveInputField` 看到 `meta.inputs.size() == 2`，无法判断这一步是要把
`age` 还是 `income` 转 tensor——挑错就会静默写错入参。所以抛
`"步骤 to_tensor 未指定 field，且模型有 2 个输入 [age, income]，请显式声明 field 参数"`。

根本原因：`ToTensor.execute` 设计上**一次只返回一个 entry**：
```java
Map<String, Object> result = new HashMap<>();
result.put(field, tensor);   // 只 put 一个
return result;
```

两步合并不了，必须分两个 `to_tensor` 步骤，每个指定一个 field。

## 多输入写法

### 写法 1：不写 preprocess（自动管线）

`ModelManager.buildAutoPreprocessSteps` 会按 ONNX inputs 自动生成：
```
parse_json → to_tensor(field=age) → to_tensor(field=income)
```

yml 留空即可：
```yaml
name: my-model
version: "1.0"
# 不声明 preprocess
```

### 写法 2：显式线性

```yaml
preprocess:
  - step: parse_json
  - step: to_tensor
    params: { field: age }
  - step: to_tensor
    params: { field: income }
```

### 写法 3：DAG 并行

两个 `to_tensor` 互相独立，可以并行跑：
```yaml
preprocess:
  - id: parse
    op: parse_json
  - id: ta
    op: to_tensor
    params: { field: age }
    inputs: [parse]
  - id: tb
    op: to_tensor
    params: { field: income }
    inputs: [parse]      # ta、tb 同一拓扑层，PipelineExecutor 会并行执行
```

## 多输出写法

`PipelinePostprocessor.process` 启动时会把所有 ONNX output 放进 context（`base/.../PipelinePostprocessor.java:38-40`）：
```java
for (Map.Entry<String, OnnxTensor> entry : output.entrySet()) {
    context.put(entry.getKey(), entry.getValue().getValue());
}
```

后续每个步骤按 `field` 各取所需：
```yaml
postprocess:
  - step: sigmoid
    params: { field: score }
  - step: argmax
    params: { field: label, axis: 1 }
```

最终响应包含 context 里所有 entry（`_meta` 已经在 process 末尾被剔除）。

## 调用方约定

**JSON 顶层 key 必须等于 ONNX input 名**。`parse_json` 没有重命名机制——顶层 key 直接展平进 context，到了 `to_tensor` 默认按 ONNX input 名找字段，找不到就抛 `"字段 X 不存在"`。这条约束是天然形成的，不需要额外校验代码。

## 简化空间（暂不实施）

### 输入侧可以放宽

观察当前多输入用法——所有 `to_tensor` 步骤做的是**同一件事**：按 ONNX input 名转 tensor，只是名字不同。完全可以让 `ToTensor.execute` 在 `field` 缺省、`meta.inputs.size() > 1` 时遍历所有 input 一次性转换。

实现思路（在 `ToTensor.execute` 里加分支）：
```java
String field = readField(params);
if (field != null) {
    // 单 field 路径，原逻辑不变
} else if (meta.inputs.size() == 1) {
    // 单输入默认，原逻辑不变
} else {
    // 新路径：遍历 meta.inputs，每个都 createTensor
    Map<String, Object> result = new HashMap<>();
    for (TensorMeta tm : meta.getInputs()) {
        Object value = input.get(tm.getName());
        if (value == null) throw new IllegalArgumentException("字段 " + tm.getName() + " 不存在");
        result.put(tm.getName(), createTensor(value, tm));
    }
    return result;
}
```

改造后 yml 可以瘦到：
```yaml
preprocess:
  - step: parse_json
  - step: to_tensor    # 自动转所有 input
```

同时 `buildAutoPreprocessSteps` 也可以化简成两步固定写死。

### 输出侧不应该这样做

postprocess 步骤语义**异质**——score 走 sigmoid、label 走 argmax 是常见配置。如果 `sigmoid` 缺省时遍历所有 output，会把
label 也 sigmoid 一遍，结果错。所以输出侧每个步骤绑定一个具体 field 是对的，不能照搬。

### 为什么暂不实施

等有真实多输入模型验证后再做。当前版本通过显式写多个 `to_tensor` 步骤或依赖自动管线已能正确支持多输入，简化只是体感优化、不影响正确性。
