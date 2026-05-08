package com.kudosol.ai.inference.operator;

import java.util.Map;

public interface Operator {

    String name();

    Map<String, Object> execute(Map<String, Object> input, Map<String, Object> params);
}
