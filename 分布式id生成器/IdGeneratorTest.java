package com.ljf.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

public class IdGeneratorTest {

	private final static Logger log = LoggerFactory.getLogger(IdGenerator.class);
    @Autowired
    private IdGenerator idGenerator = new IdGenerator();

    @Test
    public void testBatchId() {
        for (int i = 0; i < 100; i++) {
            String batchId = idGenerator.batchId(1001, 100);
            log.info("批次号: {}", batchId);
        }
    }

    @Test
    public void testSimpleUUID() {
        for (int i = 0; i < 100; i++) {
            String simpleUUID = idGenerator.simpleUUID();
            log.info("simpleUUID: {}", simpleUUID);
        }
    }

    @Test
    public void testRandomUUID() {
        for (int i = 0; i < 100; i++) {
            String randomUUID = idGenerator.randomUUID();
            log.info("randomUUID: {}", randomUUID);
        }
    }

    @Test
    public void testObjectID() {
        for (int i = 0; i < 100; i++) {
            String objectId = idGenerator.objectId();
            log.info("objectId: {}", objectId);
        }
    }

    @Test
    public void testSnowflakeId() {
        ExecutorService executorService = Executors.newFixedThreadPool(20);
        for (int i = 0; i < 20; i++) {
            executorService.execute(() -> {
                log.info("分布式 ID: {}", idGenerator.snowflakeId());
            });
        }
        executorService.shutdown();
    }

}