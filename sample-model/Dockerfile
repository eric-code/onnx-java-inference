# 模式1：传统构建，模型文件打包进镜像（本地开发 / 无 S3 场景）
ARG BASE_IMAGE=harbor.tianyishuju.com/skyease/onnx-java-inference-base:1.0.0
FROM ${BASE_IMAGE}

COPY model.onnx /models/sample-model/model.onnx
COPY model.yml /models/sample-model/model.yml
COPY target/onnx-java-inference-sample-*-preprocessor.jar /models/sample-model/preprocessor/preprocessor.jar
COPY target/onnx-java-inference-sample-*-postprocessor.jar /models/sample-model/postprocessor/postprocessor.jar

# 模式2：S3 拉取模式，镜像只包含 base，启动时从 S3 下载模型
# 不 COPY 任何模型文件，通过环境变量配置 S3 下载：
# ENV S3_ENABLED=true
# ENV S3_ENDPOINT=https://minio.example.com
# ENV S3_BUCKET=models
# ENV S3_MODELS=sample-model/sample-model.tar.gz
# ENV S3_PATH_STYLE_ACCESS=true
# ENV S3_ACCESS_KEY / S3_SECRET_KEY 通过运行时环境变量或 K8s Secret 注入
