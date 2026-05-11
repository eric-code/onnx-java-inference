package com.kudosol.ai.inference.step;

import java.util.Map;

public interface Step {

    String name();

    Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params);
}
