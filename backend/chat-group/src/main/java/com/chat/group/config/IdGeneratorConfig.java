package com.chat.group.config;

import com.chat.group.util.IdGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class IdGeneratorConfig {

    /**
     * 不配 id.worker-id 时按端口取模：同一台机器上的几个服务天然拿到不同节点号。
     * 同一服务多实例（两台机器同端口）必须显式配 id.worker-id，否则会同号撞主键。
     */
    @Bean
    public IdGenerator idGenerator(@Value("${id.worker-id:#{null}}") Integer workerId,
                                   @Value("${server.port:0}") int serverPort) {
        if (workerId != null) {
            return new IdGenerator(workerId);
        }
        return new IdGenerator(serverPort & IdGenerator.MAX_WORKER_ID);
    }
}
