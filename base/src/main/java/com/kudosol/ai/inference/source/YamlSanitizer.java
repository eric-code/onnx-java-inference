package com.kudosol.ai.inference.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class YamlSanitizer {

    private static final String YAML_PRINTABLE =
            "[^\\x09\\x0A\\x0D\\x20-\\x7E\\x85\\xA0-\\uD7FF\\uE000-\\uFFFD]";

    private YamlSanitizer() {}

    public static String sanitize(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
                .replaceAll(YAML_PRINTABLE, "");
    }
}
