package com.kudosol.ai.inference.engine;

import ai.onnxruntime.OrtSession;
import com.kudosol.ai.inference.spi.Postprocessor;
import com.kudosol.ai.inference.spi.Preprocessor;
import lombok.Getter;

@Getter
public class ModelContainer implements AutoCloseable {

    private final String name;
    private final String version;
    private final OrtSession session;
    private final Preprocessor preprocessor;
    private final Postprocessor postprocessor;

    public ModelContainer(String name, String version, OrtSession session,
                          Preprocessor preprocessor, Postprocessor postprocessor) {
        this.name = name;
        this.version = version;
        this.session = session;
        this.preprocessor = preprocessor;
        this.postprocessor = postprocessor;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (Exception e) {
            // ignore
        }
    }
}
