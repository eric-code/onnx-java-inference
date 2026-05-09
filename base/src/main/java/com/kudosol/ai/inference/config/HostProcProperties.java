package com.kudosol.ai.inference.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "host-proc")
public class HostProcProperties {

    /**
     * 宿主机 /proc 的挂载路径，默认 /host/proc
     */
    private String basePath = "/host/proc";
}
