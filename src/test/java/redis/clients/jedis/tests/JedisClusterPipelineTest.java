package redis.clients.jedis.tests;

import java.util.*;

import org.hamcrest.CoreMatchers;
import org.hamcrest.Matcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import redis.clients.jedis.*;
import redis.clients.jedis.args.ClusterResetType;
import redis.clients.jedis.args.ListPosition;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.LPosParams;
import redis.clients.jedis.params.SortingParams;
import redis.clients.jedis.providers.ClusterConnectionProvider;
import redis.clients.jedis.resps.Tuple;
import redis.clients.jedis.tests.utils.JedisClusterTestUtil;
import redis.clients.jedis.util.SafeEncoder;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static redis.clients.jedis.Protocol.CLUSTER_HASHSLOTS;

public class JedisClusterPipelineTest {
  private static Jedis node1;
  private static Jedis node2;
  private static Jedis node3;
  private static final String LOCAL_IP = "127.0.0.1";
  private static final int DEFAULT_TIMEOUT = 2000;
  private static final int DEFAULT_REDIRECTIONS = 5;
  private static final ConnectionPoolConfig DEFAULT_POOL_CONFIG = new ConnectionPoolConfig();
  private static final DefaultJedisClientConfig DEFAULT_CLIENT_CONFIG = DefaultJedisClientConfig
      .builder().password("cluster").build();

  private HostAndPort nodeInfo1 = HostAndPortUtil.getClusterServers().get(0);
  private HostAndPort nodeInfo2 = HostAndPortUtil.getClusterServers().get(1);
  private HostAndPort nodeInfo3 = HostAndPortUtil.getClusterServers().get(2);
  private Set<HostAndPort> nodes = new HashSet<>();
  {
    nodes.add(nodeInfo1);
    nodes.add(nodeInfo2);
    nodes.add(nodeInfo3);
  }

  @Before
  public void setUp() throws InterruptedException {
    node1 = new Jedis(nodeInfo1);
    node1.auth("cluster");
    node1.flushAll();

    node2 = new Jedis(nodeInfo2);
    node2.auth("cluster");
    node2.flushAll();

    node3 = new Jedis(nodeInfo3);
    node3.auth("cluster");
    node3.flushAll();

    // add nodes to cluster
    node1.clusterMeet(LOCAL_IP, nodeInfo2.getPort());
    node1.clusterMeet(LOCAL_IP, nodeInfo3.getPort());

    // split available slots across the three nodes
    int slotsPerNode = CLUSTER_HASHSLOTS / 3;
    int[] node1Slots = new int[slotsPerNode];
    int[] node2Slots = new int[slotsPerNode + 1];
    int[] node3Slots = new int[slotsPerNode];
    for (int i = 0, slot1 = 0, slot2 = 0, slot3 = 0; i < CLUSTER_HASHSLOTS; i++) {
      if (i < slotsPerNode) {
        node1Slots[slot1++] = i;
      } else if (i > slotsPerNode * 2) {
        node3Slots[slot3++] = i;
      } else {
        node2Slots[slot2++] = i;
      }
    }

    node1.clusterAddSlots(node1Slots);
    node2.clusterAddSlots(node2Slots);
    node3.clusterAddSlots(node3Slots);

    JedisClusterTestUtil.waitForClusterReady(node1, node2, node3);
  }

  @AfterClass
  public static void cleanUp() {
    node1.flushDB();
    node2.flushDB();
    node3.flushDB();
    node1.clusterReset(ClusterResetType.SOFT);
    node2.clusterReset(ClusterResetType.SOFT);
    node3.clusterReset(ClusterResetType.SOFT);
  }

  @After
  public void tearDown() throws InterruptedException {
    cleanUp();
  }

  @Test
  public void clusterPipelineSync() {
    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline clusterPipeline = new ClusterPipeline(provider);

    Response<String> r1 = clusterPipeline.set("key1", "value1");
    Response<String> r2 = clusterPipeline.set("key2", "value2");
    Response<String> r3 = clusterPipeline.set("key3", "value3");
    Response<String> r4 = clusterPipeline.get("key1");
    Response<String> r5 = clusterPipeline.get("key2");
    Response<String> r6 = clusterPipeline.get("key3");

    clusterPipeline.sync();
    Assert.assertEquals("OK", r1.get());
    Assert.assertEquals("OK", r2.get());
    Assert.assertEquals("OK", r3.get());
    Assert.assertEquals("value1", r4.get());
    Assert.assertEquals("value2", r5.get());
    Assert.assertEquals("value3", r6.get());
  }

  @Test
  public void clusterPipelineSort() {
    List<String> sorted = new ArrayList<>();
    sorted.add("1");
    sorted.add("2");
    sorted.add("3");
    sorted.add("4");
    sorted.add("5");

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);

    Response<Long> r1 = p.rpush("key1", "2","3","5","1","4");
    Response<List<String>> r2 = p.sort("key1");
    Response<Long> r3 = p.sort("key1", "key1");
    Response<List<String>> r4 = p.lrange("key1", 0, 4);
    Response<List<String>> r5 = p.sort("key1", new SortingParams().limit(0, 2));
    Response<Long> r6 = p.sort("key1", new SortingParams().desc(), "key1");
    Response<List<String>> r7 = p.lrange("key1", 0, 4);

    p.sync();
    Assert.assertEquals(Long.valueOf(5), r1.get());
    Assert.assertEquals(sorted, r2.get());
    Assert.assertEquals(Long.valueOf(5), r3.get());
    Assert.assertEquals(sorted, r4.get());
    Assert.assertEquals(2, r5.get().size());
    Assert.assertEquals(Long.valueOf(5), r6.get());
    Collections.reverse(sorted);
    Assert.assertEquals(sorted, r7.get());
  }

  @Test
  public void clusterPipelineList() {
    List<String> vals = new ArrayList<>();
    vals.add("foobar");

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);

    Response<Long> r1 = p.lpush("mylist", "hello", "hello", "foo", "foo"); // ["foo", "foo", "hello", "hello"]
    Response<Long> r2 = p.lpos("mylist", "foo");
    Response<Long> r3 = p.lpos("mylist", "foo", new LPosParams().maxlen(1));
    Response<List<Long>> r4 = p.lpos("mylist", "foo", new LPosParams().maxlen(1), 2);
    Response<String> r5 = p.ltrim("mylist", 2, 3); // ["hello", "hello"]
    Response<Long> r6 = p.llen("mylist");
    Response<String> r7 = p.lindex("mylist", -1);
    Response<String> r8 = p.lset("mylist", 1, "foobar"); // ["hello", "foobar"]
    Response<Long> r9 = p.lrem("mylist", 1, "hello"); // ["foobar"]
    Response<List<String>> r10 = p.lrange("mylist",0,10);
    Response<List<String>> r11 = p.lpop("mylist", 1); // ["foobar"]
    Response<Long> r12 = p.rpush("mylist", "hello", "hello", "foo", "foo");  // ["hello", "hello", "foo", "foo"]
    Response<String> r13 = p.rpop("mylist"); // ["hello", "hello", "foo"]
    Response<List<String>> r14 = p.rpop("mylist", 2); // ["hello"]
    Response<Long> r15 = p.linsert("mylist", ListPosition.AFTER, "hello", "world"); // ["hello", "world"]
    Response<Long> r16 = p.lpushx("myotherlist", "foo", "bar");
    Response<Long> r17 = p.rpushx("myotherlist", "foo", "bar");
    //Response<String> r18 = p.rpoplpush("mylist", "myotherlist");

    p.sync();
    Assert.assertEquals(Long.valueOf(4), r1.get());
    Assert.assertEquals(Long.valueOf(0), r2.get());
    Assert.assertEquals(Long.valueOf(0), r3.get());
    Assert.assertEquals(1, r4.get().size());
    Assert.assertEquals("OK", r5.get());
    Assert.assertEquals(Long.valueOf(2), r6.get());
    Assert.assertEquals("hello", r7.get());
    Assert.assertEquals("OK", r8.get());
    Assert.assertEquals(Long.valueOf(1), r9.get());
    Assert.assertEquals(vals, r10.get());
    Assert.assertEquals(vals, r11.get());
    Assert.assertEquals(Long.valueOf(4), r12.get());
    Assert.assertEquals("foo", r13.get());
    Assert.assertEquals(2, r14.get().size());
    Assert.assertEquals(Long.valueOf(2), r15.get());
    Assert.assertEquals(Long.valueOf(0), r16.get());
    Assert.assertEquals(Long.valueOf(0), r17.get());
    //Assert.assertEquals("hello", r18.get());
  }


  @Test
  public void clusterPipelineHash() {
    Map <String, String> hm = new HashMap<>();
    hm.put("field2", "2");
    hm.put("field3", "5");

    Set<String> keys = new HashSet<>();
    keys.add("field1");
    keys.add("field2");

    List<String> vals = new ArrayList<>();
    vals.add("hello");
    vals.add("3.5");

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);

    Response<Long> r1 = p.hset("myhash", "field1","hello");
    Response<Long> r2 = p.hsetnx("myhash", "field1","hello");
    Response<String> r3 = p.hget("myhash", "field1");
    Response<Long> r4 = p.hset("myhash", hm);
    Response<String> r5 = p.hmset("mymhash", hm);
    p.hincrBy("myhash", "field2", 1);
    Response<Double> r6 = p.hincrByFloat("myhash", "field2", 0.5);
    Response<Long> r7 = p.hlen("myhash");
    Response<Long> r8 = p.hdel("myhash", "field3");
    Response<Boolean> r9 = p.hexists("myhash", "field3");
    Response<Set<String>> r10 = p.hkeys("myhash");
    Response<List<String>> r11 = p.hvals("myhash");
    Response<String> r12 = p.hrandfield("myhash");
    Response<List<String>> r13 = p.hrandfield("myhash", 2);
    Response<Map<String, String>> r14 = p.hrandfieldWithValues("myhash", 2);
    Response<Long> r15 = p.hstrlen("myhash", "field1");

    p.sync();
    Assert.assertEquals(Long.valueOf(1), r1.get());
    Assert.assertEquals(Long.valueOf(0), r2.get());
    Assert.assertEquals("hello", r3.get());
    Assert.assertEquals(Long.valueOf(2), r4.get());
    Assert.assertEquals("OK", r5.get());
    Assert.assertEquals(Double.valueOf(3.5), r6.get());
    Assert.assertEquals(Long.valueOf(3), r7.get());
    Assert.assertEquals(Long.valueOf(1), r8.get());
    Assert.assertFalse(r9.get());
    Assert.assertEquals(keys, r10.get());
    Assert.assertEquals(vals, r11.get());
    Assert.assertTrue(keys.contains(r12.get()));
    Assert.assertEquals(2, r13.get().size());
    Assert.assertTrue(r14.get().containsKey("field1") && r14.get().containsValue("hello"));
    Assert.assertEquals(Long.valueOf(5), r15.get());
  }

  @Test
  public void pipelineResponse() {
    try (JedisCluster jc = new JedisCluster(nodes, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT,
        DEFAULT_REDIRECTIONS, "cluster", DEFAULT_POOL_CONFIG)) {
      jc.set("string", "foo");
      jc.lpush("list", "foo");
      jc.hset("hash", "foo", "bar");
      jc.zadd("zset", 1, "foo");
      jc.sadd("set", "foo");
      jc.setrange("setrange", 0, "0123456789");
      byte[] bytesForSetRange = { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9 };
      jc.setrange("setrangebytes".getBytes(), 0, bytesForSetRange);
    }

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);

    Response<String> string = p.get("string");
    Response<String> list = p.lpop("list");
    Response<String> hash = p.hget("hash", "foo");
    Response<Set<String>> zset = p.zrange("zset", 0, -1);
    Response<String> set = p.spop("set");
    Response<Boolean> blist = p.exists("list");
    Response<Double> zincrby = p.zincrby("zset", 1, "foo");
    Response<Long> zcard = p.zcard("zset");
    p.lpush("list", "bar");
    Response<List<String>> lrange = p.lrange("list", 0, -1);
    Response<Map<String, String>> hgetAll = p.hgetAll("hash");
    p.sadd("set", "foo");
    Response<Set<String>> smembers = p.smembers("set");
    Response<Set<Tuple>> zrangeWithScores = p.zrangeWithScores("zset", 0, -1);
    Response<String> getrange = p.getrange("setrange", 1, 3);
    Response<byte[]> getrangeBytes = p.getrange("setrangebytes".getBytes(), 6, 8);
    p.sync();

    assertEquals("foo", string.get());
    assertEquals("foo", list.get());
    assertEquals("bar", hash.get());
    assertEquals("foo", zset.get().iterator().next());
    assertEquals("foo", set.get());
    assertEquals(false, blist.get());
    assertEquals(Double.valueOf(2), zincrby.get());
    assertEquals(Long.valueOf(1), zcard.get());
    assertEquals(1, lrange.get().size());
    assertNotNull(hgetAll.get().get("foo"));
    assertEquals(1, smembers.get().size());
    assertEquals(1, zrangeWithScores.get().size());
    assertEquals("123", getrange.get());
    byte[] expectedGetRangeBytes = { 6, 7, 8 };
    assertArrayEquals(expectedGetRangeBytes, getrangeBytes.get());
  }

  @Test
  public void pipelineBinarySafeHashCommands() {
    try (JedisCluster jc = new JedisCluster(nodes, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT,
        DEFAULT_REDIRECTIONS, "cluster", DEFAULT_POOL_CONFIG)) {
      jc.hset("key".getBytes(), "f1".getBytes(), "v111".getBytes());
      jc.hset("key".getBytes(), "f22".getBytes(), "v2222".getBytes());
    }


    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Map<byte[], byte[]>> fmap = p.hgetAll("key".getBytes());
    Response<Set<byte[]>> fkeys = p.hkeys("key".getBytes());
    Response<List<byte[]>> fordered = p.hmget("key".getBytes(), "f22".getBytes(), "f1".getBytes());
    Response<List<byte[]>> fvals = p.hvals("key".getBytes());
    p.sync();

    assertNotNull(fmap.get());
    // we have to do these strange contortions because byte[] is not a very
    // good key
    // for a java Map. It only works with equality (you need the exact key
    // object to retrieve
    // the value) I recommend we switch to using ByteBuffer or something
    // similar:
    // http://stackoverflow.com/questions/1058149/using-a-byte-array-as-hashmap-key-java
    Map<byte[], byte[]> map = fmap.get();
    Set<byte[]> mapKeys = map.keySet();
    Iterator<byte[]> iterMap = mapKeys.iterator();
    byte[] firstMapKey = iterMap.next();
    byte[] secondMapKey = iterMap.next();
    assertFalse(iterMap.hasNext());
    verifyHasBothValues(firstMapKey, secondMapKey, "f1".getBytes(), "f22".getBytes());
    byte[] firstMapValue = map.get(firstMapKey);
    byte[] secondMapValue = map.get(secondMapKey);
    verifyHasBothValues(firstMapValue, secondMapValue, "v111".getBytes(), "v2222".getBytes());

    assertNotNull(fkeys.get());
    Iterator<byte[]> iter = fkeys.get().iterator();
    byte[] firstKey = iter.next();
    byte[] secondKey = iter.next();
    assertFalse(iter.hasNext());
    verifyHasBothValues(firstKey, secondKey, "f1".getBytes(), "f22".getBytes());

    assertNotNull(fordered.get());
    assertArrayEquals("v2222".getBytes(), fordered.get().get(0));
    assertArrayEquals("v111".getBytes(), fordered.get().get(1));

    assertNotNull(fvals.get());
    assertEquals(2, fvals.get().size());
    byte[] firstValue = fvals.get().get(0);
    byte[] secondValue = fvals.get().get(1);
    verifyHasBothValues(firstValue, secondValue, "v111".getBytes(), "v2222".getBytes());
  }

  private void verifyHasBothValues(byte[] firstKey, byte[] secondKey, byte[] value1, byte[] value2) {
    assertFalse(Arrays.equals(firstKey, secondKey));
    assertTrue(Arrays.equals(firstKey, value1) || Arrays.equals(firstKey, value2));
    assertTrue(Arrays.equals(secondKey, value1) || Arrays.equals(secondKey, value2));
  }

  @Test(expected = IllegalStateException.class)
  public void pipelineResponseWithinPipeline() {
    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<String> string = p.get("string");
    string.get();
    p.sync();
  }

  @Test
  public void pipelineWithPubSub() {
    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline pipelined = new ClusterPipeline(provider);
    Response<Long> p1 = pipelined.publish("foo", "bar");
    Response<Long> p2 = pipelined.publish("foo".getBytes(), "bar".getBytes());
    pipelined.sync();
    assertEquals(0, p1.get().longValue());
    assertEquals(0, p2.get().longValue());
  }

  @Test
  public void canRetrieveUnsetKey() {
    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<String> shouldNotExist = p.get(UUID.randomUUID().toString());
    p.sync();
    assertNull(shouldNotExist.get());
  }

  @Test
  public void piplineWithError() {
    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    p.set("foo", "bar");
    Response<Set<String>> error = p.smembers("foo");
    Response<String> r = p.get("foo");
    p.sync();
    try {
      error.get();
      fail();
    } catch (JedisDataException e) {
      // that is fine we should be here
    }
    assertEquals(r.get(), "bar");
  }

  @Test
  public void testEval() {
    String script = "return 'success!'";

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Object> result = p.eval(script);
    p.sync();

    assertEquals("success!", result.get());
  }

  @Test
  public void testEvalWithBinary() {
    String script = "return 'success!'";

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Object> result = p.eval(SafeEncoder.encode(script));
    p.sync();

    assertArrayEquals(SafeEncoder.encode("success!"), (byte[]) result.get());
  }

  @Test
  public void testEvalKeyAndArg() {
    String key = "test";
    String arg = "3";
    String script = "redis.call('INCRBY', KEYS[1], ARGV[1]) redis.call('INCRBY', KEYS[1], ARGV[1])";

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    p.set(key, "0");
    Response<Object> result0 = p.eval(script, Arrays.asList(key), Arrays.asList(arg));
    p.incr(key);
    Response<Object> result1 = p.eval(script, Arrays.asList(key), Arrays.asList(arg));
    Response<String> result2 = p.get(key);
    p.sync();

    assertNull(result0.get());
    assertNull(result1.get());
    assertEquals("13", result2.get());
  }

  @Test
  public void testEvalKeyAndArgWithBinary() {
    // binary
    byte[] bKey = SafeEncoder.encode("test");
    byte[] bArg = SafeEncoder.encode("3");
    byte[] bScript = SafeEncoder
        .encode("redis.call('INCRBY', KEYS[1], ARGV[1]) redis.call('INCRBY', KEYS[1], ARGV[1])");

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline bP = new ClusterPipeline(provider);
    bP.set(bKey, SafeEncoder.encode("0"));
    Response<Object> bResult0 = bP.eval(bScript, Arrays.asList(bKey), Arrays.asList(bArg));
    bP.incr(bKey);
    Response<Object> bResult1 = bP.eval(bScript, Arrays.asList(bKey), Arrays.asList(bArg));
    Response<byte[]> bResult2 = bP.get(bKey);
    bP.sync();

    assertNull(bResult0.get());
    assertNull(bResult1.get());
    assertArrayEquals(SafeEncoder.encode("13"), bResult2.get());
  }

  @Test
  public void testEvalNestedLists() {
    String script = "return { {KEYS[1]} , {2} }";

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Object> result = p.eval(script, 1, "key1");
    p.sync();

    List<?> results = (List<?>) result.get();
    assertThat((List<String>) results.get(0), listWithItem("key1"));
    assertThat((List<Long>) results.get(1), listWithItem(2L));
  }

  @Test
  public void testEvalNestedListsWithBinary() {
    byte[] bScript = SafeEncoder.encode("return { {KEYS[1]} , {2} }");
    byte[] bKey = SafeEncoder.encode("key1");

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Object> result = p.eval(bScript, 1, bKey);
    p.sync();

    List<?> results = (List<?>) result.get();
    assertThat((List<byte[]>) results.get(0), listWithItem(bKey));
    assertThat((List<Long>) results.get(1), listWithItem(2L));
  }

  @Test
  public void testEvalsha() {
    String script = "return 'success!'";
    String sha1;
    try (JedisCluster jc = new JedisCluster(nodes, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT,
        DEFAULT_REDIRECTIONS, "cluster", DEFAULT_POOL_CONFIG)) {
      sha1 = jc.scriptLoad(script, "sampleKey");
      assertTrue(jc.scriptExists(sha1, "sampleKey"));
    }

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    Response<Object> result = p.evalsha(sha1, 1, "sampleKey");
    p.sync();

    assertEquals("success!", result.get());
  }

  @Test
  public void testEvalshaKeyAndArg() {
    String key = "test";
    String arg = "3";
    String script = "redis.call('INCRBY', KEYS[1], ARGV[1]) redis.call('INCRBY', KEYS[1], ARGV[1])";
    String sha1;
    try (JedisCluster jc = new JedisCluster(nodes, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT,
        DEFAULT_REDIRECTIONS, "cluster", DEFAULT_POOL_CONFIG)) {
      sha1 = jc.scriptLoad(script, key);
      assertTrue(jc.scriptExists(sha1, key));
    }

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    p.set(key, "0");
    Response<Object> result0 = p.evalsha(sha1, Arrays.asList(key), Arrays.asList(arg));
    p.incr(key);
    Response<Object> result1 = p.evalsha(sha1, Arrays.asList(key), Arrays.asList(arg));
    Response<String> result2 = p.get(key);
    p.sync();

    assertNull(result0.get());
    assertNull(result1.get());
    assertEquals("13", result2.get());
  }

  @Test
  public void testEvalshaKeyAndArgWithBinary() {
    byte[] bKey = SafeEncoder.encode("test");
    byte[] bArg = SafeEncoder.encode("3");
    String script = "redis.call('INCRBY', KEYS[1], ARGV[1]) redis.call('INCRBY', KEYS[1], ARGV[1])";
    byte[] bScript = SafeEncoder.encode(script);
    byte[] bSha1;
    try (JedisCluster jc = new JedisCluster(nodes, DEFAULT_TIMEOUT, DEFAULT_TIMEOUT,
        DEFAULT_REDIRECTIONS, "cluster", DEFAULT_POOL_CONFIG)) {
      bSha1 = jc.scriptLoad(bScript, bKey);
      assertTrue(jc.scriptExists(bSha1, bKey));
    }

    ClusterConnectionProvider provider = new ClusterConnectionProvider(nodes, DEFAULT_CLIENT_CONFIG);
    ClusterPipeline p = new ClusterPipeline(provider);
    p.set(bKey, SafeEncoder.encode("0"));
    Response<Object> result0 = p.evalsha(bSha1, Arrays.asList(bKey), Arrays.asList(bArg));
    p.incr(bKey);
    Response<Object> result1 = p.evalsha(bSha1, Arrays.asList(bKey), Arrays.asList(bArg));
    Response<byte[]> result2 = p.get(bKey);
    p.sync();

    assertNull(result0.get());
    assertNull(result1.get());
    assertArrayEquals(SafeEncoder.encode("13"), result2.get());
  }

  private <T> Matcher<Iterable<? super T>> listWithItem(T expected) {
    return CoreMatchers.<T> hasItem(equalTo(expected));
  }
}
