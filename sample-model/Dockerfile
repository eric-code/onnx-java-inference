FROM harbor.tianyishuju.com/skyease/onnx-java-inference-base:1.0.0

COPY model.onnx /models/sample-model/model.onnx
COPY model.yml /models/sample-model/model.yml
COPY target/onnx-java-inference-sample-*-preprocessor.jar /models/sample-model/preprocessor/preprocessor.jar
COPY target/onnx-java-inference-sample-*-postprocessor.jar /models/sample-model/postprocessor/postprocessor.jar
