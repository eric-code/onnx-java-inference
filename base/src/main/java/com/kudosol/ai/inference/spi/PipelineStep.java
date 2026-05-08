package com.kudosol.ai.inference.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PipelineStep {

    private String id;
    private String op;
    private Map<String, Object> params;
    private List<String> inputs;
}
