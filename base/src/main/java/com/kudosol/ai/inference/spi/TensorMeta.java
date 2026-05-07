package com.kudosol.ai.inference.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TensorMeta {

    private String name;
    private String type;
    private List<Long> shape;
}
