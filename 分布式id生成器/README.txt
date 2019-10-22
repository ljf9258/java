## 分布式id生成器

###### 在高并发或者分表分库情况下怎么保证数据id的幂等性呢?

###### 经常用到的解决方案有以下几种。

    微软公司通用唯一识别码（UUID）
    Twitter公司雪花算法（SnowFlake）
    基于数据库的id自增
    对id进行缓存

###### 这里我们要谈到snowflake算法了

snowflake是Twitter开源的分布式ID生成算法，结果是一个long型的ID。其核心思想是：使用41bit作为毫秒数，10bit作为机器的ID（5个bit是数据中心，5个bit的机器ID），12bit作为毫秒内的流水号，最后还有一个符号位，永远是0。

snowflake算法所生成的ID结构
![snowflake](https://github.com/ljf9258/java/blob/master/%E5%88%86%E5%B8%83%E5%BC%8Fid%E7%94%9F%E6%88%90%E5%99%A8/snowflake.jpg?raw=true)

整个结构是64位，所以我们在Java中可以使用long来进行存储。该算法实现基本就是二进制操作,单机每秒内理论上最多可以生成1024*(2^12)，也就是409.6万个ID(1024 X 4096 = 4194304)

- 0 - 0000000000 0000000000 0000000000 0000000000 0 - 00000 - 00000 - 000000000000
- 1位标识，由于long基本类型在Java中是带符号的，最高位是符号位，正数是0，负数是1，所以id一般是正数，最高位是0
- 41位时间截(毫秒级)，注意，41位时间截不是存储当前时间的时间截，而是存储时间截的差值（当前时间截 - 开始时间截)得到的值，这里  的开始时间截，一般是我们的id生成器开始使用的时间，由我们程序来指定的（如下下面程序IdWorker类的startTime属性）。41位的时间截，可以使用69年，年T = (1L << 41) / (1000L * 60 * 60 * 24 * 365) = 69
- 10位的数据机器位，可以部署在1024个节点，包括5位datacenterId和5位workerId
- 12位序列，毫秒内的计数，12位的计数顺序号支持每个节点每毫秒(同一机器，同一时间截)产生4096个ID序号
- 加起来刚好64位，为一个Long型。
- SnowFlake的优点是，整体上按照时间自增排序，并且整个分布式系统内不会产生ID碰撞(由数据中心ID和机器ID作区分)，并且效率较高，经测试，SnowFlake每秒能够产生26万ID左右。

###### snowFlake算法的优点：

1. 生成ID时不依赖于DB，完全在内存生成，高性能高可用。
2. ID呈趋势递增，后续插入索引树的时候性能较好。

###### SnowFlake算法的缺点：

依赖于系统时钟的一致性。如果某台机器的系统时钟回拨，有可能造成ID冲突，或者ID乱序

###### 算法代码如下

```java
public class SnowflakeIdWorker {
 // ==============================Fields==================
    /** 开始时间截 (2019-08-06) */
    private final long twepoch = 1565020800000L;

    /** 机器id所占的位数 */
    private final long workerIdBits = 5L;

    /** 数据标识id所占的位数 */
    private final long datacenterIdBits = 5L;

    /** 支持的最大机器id，结果是31 (这个移位算法可以很快的计算出几位二进制数所能表示的最大十进制数) */
    private final long maxWorkerId = -1L ^ (-1L << workerIdBits);

    /** 支持的最大数据标识id，结果是31 */
    private final long maxDatacenterId = -1L ^ (-1L << datacenterIdBits);

    /** 序列在id中占的位数 */
    private final long sequenceBits = 12L;

    /** 机器ID向左移12位 */
    private final long workerIdShift = sequenceBits;

    /** 数据标识id向左移17位(12 5) */
    private final long datacenterIdShift = sequenceBits   workerIdBits;

    /** 时间截向左移22位(5 5 12) */
    private final long timestampLeftShift = sequenceBits   workerIdBits   datacenterIdBits;

    /** 生成序列的掩码，这里为4095 (0b111111111111=0xfff=4095) */
    private final long sequenceMask = -1L ^ (-1L << sequenceBits);

    /** 工作机器ID(0~31) */
    private long workerId;

    /** 数据中心ID(0~31) */
    private long datacenterId;

    /** 毫秒内序列(0~4095) */
    private long sequence = 0L;

    /** 上次生成ID的时间截 */
    private long lastTimestamp = -1L;

     //==============================Constructors====================
    /**
     * 构造函数
     * @param workerId 工作ID (0~31)
     * @param datacenterId 数据中心ID (0~31)
     */
    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException(String.format(worker Id can\'t be greater than %d or less than 0, maxWorkerId));
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException(String.format(datacenter Id can\'t be greater than %d or less than 0, maxDatacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    // ==============================Methods=================================
    /**
     * 获得下一个ID (该方法是线程安全的)
     * @return SnowflakeId
     */
    public synchronized long nextId() {
        long timestamp = timeGen();

        //如果当前时间小于上一次ID生成的时间戳，说明系统时钟回退过这个时候应当抛出异常
        if (timestamp < lastTimestamp) {
            throw new RuntimeException(
                    String.format(Clock moved backwards.  Refusing to generate id for %d milliseconds, lastTimestamp - timestamp));
        }

        //如果是同一时间生成的，则进行毫秒内序列
        if (lastTimestamp == timestamp) {
            sequence = (sequence   1) & sequenceMask;
            //毫秒内序列溢出
            if (sequence == 0) {
                //阻塞到下一个毫秒,获得新的时间戳
                timestamp = tilNextMillis(lastTimestamp);
            }
        }
        //时间戳改变，毫秒内序列重置
        else {
            sequence = 0L;
        }

        //上次生成ID的时间截
        lastTimestamp = timestamp;

        //移位并通过或运算拼到一起组成64位的ID
        return ((timestamp - twepoch) << timestampLeftShift) //
                | (datacenterId << datacenterIdShift) //
                | (workerId << workerIdShift) //
                | sequence;
    }

    /**
     * 阻塞到下一个毫秒，直到获得新的时间戳
     * @param lastTimestamp 上次生成ID的时间截
     * @return 当前时间戳
     */
    protected long tilNextMillis(long lastTimestamp) {
        long timestamp = timeGen();
        while (timestamp <= lastTimestamp) {
            timestamp = timeGen();
        }
        return timestamp;
    }

    /**
     * 返回以毫秒为单位的当前时间
     * @return 当前时间(毫秒)
     */
    protected long timeGen() {
        return System.currentTimeMillis();
    }

    //==============================Test=============================================
    /** 测试 */
    public static void main(String[] args) {
        SnowflakeIdWorker idWorker = new SnowflakeIdWorker(0, 0);
        for (int i = 0; i < 1000; i  ) {
            long id = idWorker.nextId();
            System.out.println(Long.toBinaryString(id));
            System.out.println(id);
        }
    }
}
```
###### 快速使用snowflake算法只需以下几步

引入hutool依赖

```xml
<dependency>
    <groupId>cn.hutool</groupId>
    <artifactId>hutool-captcha</artifactId>
    <version>5.0.2</version>
</dependency>
```
ID 生成器

```java
public class IdGenerator {

    private long workerId = 0;

    @PostConstruct
    void init() {
        try {
            workerId = NetUtil.ipv4ToLong(NetUtil.getLocalhostStr());
            log.info(当前机器 workerId: {}, workerId);
        } catch (Exception e) {
            log.warn(获取机器 ID 失败, e);
            workerId = NetUtil.getLocalhost().hashCode();
            log.info(当前机器 workerId: {}, workerId);
        }
    }

    /**
     * 获取一个批次号，形如 2019071015301361000101237
     * <p>
     * 数据库使用 char(25) 存储
     *
     * @param tenantId 租户ID，5 位
     * @param module   业务模块ID，2 位
     * @return 返回批次号
     */
    public synchronized String batchId(int tenantId, int module) {
        String prefix = DateTime.now().toString(DatePattern.PURE_DATETIME_MS_PATTERN);
        return prefix   tenantId   module   RandomUtil.randomNumbers(3);
    }

    @Deprecated
    public synchronized String getBatchId(int tenantId, int module) {
        return batchId(tenantId, module);
    }

    /**
     * 生成的是不带-的字符串，类似于：b17f24ff026d40949c85a24f4f375d42
     *
     * @return
     */
    public String simpleUUID() {
        return IdUtil.simpleUUID();
    }

    /**
     * 生成的UUID是带-的字符串，类似于：a5c8a5e8-df2b-4706-bea4-08d0939410e3
     *
     * @return
     */
    public String randomUUID() {
        return IdUtil.randomUUID();
    }

    private Snowflake snowflake = IdUtil.createSnowflake(workerId, 1);

    public synchronized long snowflakeId() {
        return snowflake.nextId();
    }

    public synchronized long snowflakeId(long workerId, long dataCenterId) {
        Snowflake snowflake = IdUtil.createSnowflake(workerId, dataCenterId);
        return snowflake.nextId();
    }

    /**
     * 生成类似：5b9e306a4df4f8c54a39fb0c
     * <p>
     * ObjectId 是 MongoDB 数据库的一种唯一 ID 生成策略，
     * 是 UUID version1 的变种，详细介绍可见：服务化框架－分布式 Unique ID 的生成方法一览。
     *
     * @return
     */
    public String objectId() {
        return ObjectId.next();
    }

}
```
测试类
```java

public class IdGeneratorTest {

    @Autowired
    private IdGenerator idGenerator;

    @Test
    public void testBatchId() {
        for (int i = 0; i < 100; i  ) {
            String batchId = idGenerator.batchId(1001, 100);
            log.info(批次号: {}, batchId);
        }
    }

    @Test
    public void testSimpleUUID() {
        for (int i = 0; i < 100; i  ) {
            String simpleUUID = idGenerator.simpleUUID();
            log.info(simpleUUID: {}, simpleUUID);
        }
    }

    @Test
    public void testRandomUUID() {
        for (int i = 0; i < 100; i  ) {
            String randomUUID = idGenerator.randomUUID();
            log.info(randomUUID: {}, randomUUID);
        }
    }

    @Test
    public void testObjectID() {
        for (int i = 0; i < 100; i  ) {
            String objectId = idGenerator.objectId();
            log.info(objectId: {}, objectId);
        }
    }

    @Test
    public void testSnowflakeId() {
        ExecutorService executorService = Executors.newFixedThreadPool(20);
        for (int i = 0; i < 20; i  ) {
            executorService.execute(() -> {
                log.info(分布式 ID: {}, idGenerator.snowflakeId());
            });
        }
        executorService.shutdown();
    }

}

```

在项目中我们只需要注入 @Autowired private IdGenerator idGenerator;即可
然后设置id order.setId(idGenerator.snowflakeId() );
