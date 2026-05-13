package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession;
import com.kudosol.ai.inference.config.InferenceProperties;
import com.kudosol.ai.inference.log.InferenceLog;
import com.kudosol.ai.inference.log.InferenceLogPublisher;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.kudosol.ai.inference.exception.BusinessException;
import com.kudosol.ai.inference.exception.RequestTimeoutException;
import com.kudosol.ai.inference.exception.TooManyRequestException;
import com.kudosol.ai.inference.exception.NotFoundException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@RequiredArgsConstructor
public class InferenceEngine {

    private final ModelManager modelManager;
    private final InferenceLogPublisher logPublisher;
    private final InferenceProperties properties;

    private Semaphore inferenceSemaphore;
    private ExecutorService inferenceExecutor;
    private final AtomicInteger inFlightCount = new AtomicInteger(0);

    @PostConstruct
    void init() {
        this.inferenceSemaphore = new Semaphore(properties.getMaxConcurrentInferences(), false);
        this.inferenceExecutor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "inference-worker");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void shutdown() {
        inferenceExecutor.shutdownNow();
    }

    public Map<String, Object> infer(String modelName, byte[] inputData, Map<String, Object> params) {
        ModelContainer container;
        try {
            container = modelManager.getModel(modelName);
        } catch (NotFoundException e) {
            logPublisher.publish(InferenceLog.error(modelName, "lookup", "模型不存在", e.getMessage()));
            throw e;
        }

        if (!inferenceSemaphore.tryAcquire()) {
            logPublisher.publish(InferenceLog.error(modelName, "concurrency",
                    "并发推理超限", "当前限制: " + properties.getMaxConcurrentInferences()));
            throw new TooManyRequestException(
                    "并发推理数已达上限 (%d)，请稍后重试".formatted(properties.getMaxConcurrentInferences()));
        }

        inFlightCount.incrementAndGet();
        try {
            Duration timeout = resolveTimeout(container);
            Future<Map<String, Object>> future = inferenceExecutor.submit(
                    () -> doInfer(modelName, container, inputData, params));
            try {
                return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                logPublisher.publish(InferenceLog.error(modelName, "timeout",
                        "推理超时", "超时限制: " + timeout));
                throw new RequestTimeoutException(
                        "模型 [%s] 推理超时 (%s)".formatted(modelName, timeout));
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof RuntimeException re) throw re;
                throw new RuntimeException(cause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RequestTimeoutException(
                        "模型 [%s] 推理被中断".formatted(modelName));
            }
        } finally {
            inFlightCount.decrementAndGet();
            inferenceSemaphore.release();
        }
    }

    private Duration resolveTimeout(ModelContainer container) {
        return container.getTimeout() != null ? container.getTimeout() : properties.getInferenceTimeout();
    }

    private Map<String, Object> doInfer(String modelName, ModelContainer container,
                                         byte[] inputData, Map<String, Object> params) {
        long totalStart = System.currentTimeMillis();

        // 前处理
        Map<String, OnnxTensor> inputs;
        long preprocessStart = System.currentTimeMillis();
        try {
            inputs = container.getPreprocessor().process(inputData, params);
            logPublisher.publish(InferenceLog.info(modelName, "preprocess", "前处理完成",
                    System.currentTimeMillis() - preprocessStart));
        } catch (BusinessException e) {
            logPublisher.publish(InferenceLog.error(modelName, "preprocess", "前处理失败", e.getMessage()));
            throw e;
        } catch (Exception e) {
            logPublisher.publish(InferenceLog.error(modelName, "preprocess", "前处理失败", e.getMessage()));
            throw new RuntimeException("模型 [%s] 前处理失败: %s".formatted(modelName, e.getMessage()), e);
        }

        // 推理 + 后处理
        long inferStart = System.currentTimeMillis();
        // OrtSession.Result 关闭时输出张量也会关闭，所以后处理必须在 result 关闭前完成
        try (OrtSession.Result result = container.getSession().run(inputs)) {
            logPublisher.publish(InferenceLog.info(modelName, "infer", "推理完成",
                    System.currentTimeMillis() - inferStart));

            Map<String, OnnxTensor> outputs = new HashMap<>();
            for (String name : container.getSession().getOutputNames()) {
                OnnxTensor tensor = (OnnxTensor) result.get(name).orElseThrow();
                outputs.put(name, tensor);
            }

            long postprocessStart = System.currentTimeMillis();
            try {
                Map<String, Object> postResult = container.getPostprocessor().process(outputs);
                logPublisher.publish(InferenceLog.info(modelName, "postprocess", "后处理完成",
                        System.currentTimeMillis() - postprocessStart));
                logPublisher.publish(InferenceLog.info(modelName, "total", "推理全流程完成",
                        System.currentTimeMillis() - totalStart));
                return postResult;
            } catch (BusinessException e) {
                logPublisher.publish(InferenceLog.error(modelName, "postprocess", "后处理失败", e.getMessage()));
                throw e;
            } catch (Exception e) {
                logPublisher.publish(InferenceLog.error(modelName, "postprocess", "后处理失败", e.getMessage()));
                throw new RuntimeException("模型 [%s] 后处理失败: %s".formatted(modelName, e.getMessage()), e);
            }
        } catch (Exception e) {
            if (e instanceof BusinessException be) throw be;
            if (e instanceof RuntimeException re && re.getMessage() != null && re.getMessage().contains("后处理失败")) {
                throw re;
            }
            logPublisher.publish(InferenceLog.error(modelName, "infer", "推理执行失败", e.getMessage()));
            throw new RuntimeException("模型 [%s] 推理执行失败: %s".formatted(modelName, e.getMessage()), e);
        } finally {
            inputs.values().forEach(OnnxTensor::close);
        }
    }

    public boolean awaitInFlight(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (inFlightCount.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return inFlightCount.get() == 0;
            }
        }
        return inFlightCount.get() == 0;
    }

    public int getInFlightCount() {
        return inFlightCount.get();
    }
}
