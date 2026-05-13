package com.kudosol.ai.inference.spi;

import lombok.Data;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Data
public class ModelMeta {

    private String name;
    private String version;
    private String description;
    private Duration timeout;
    private List<TensorMeta> inputs = new ArrayList<>();
    private List<TensorMeta> outputs = new ArrayList<>();
    private List<PipelineStep> preprocess;
    private List<PipelineStep> postprocess;
}
