package com.lmx.jredis.core;

import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.net.HostAndPort;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import redis.SerializationUtil;
import redis.clients.jedis.Jedis;
import redis.netty4.Command;
import redis.netty4.ErrorReply;
import redis.netty4.Reply;
import redis.util.BytesKey;

import javax.annotation.PostConstruct;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * @author : lucas
 * @Description: redis 调用器
 * @date Date : 2019/01/01 16:03
 */
@Slf4j
@Component
public class RedisCommandInvoker {
    private static final byte LOWER_DIFF = 'a' - 'A';
    private RedisCommandProcessor rs;
    private static final Map<BytesKey, Wrapper> methods = new ConcurrentHashMap<>();
    private LinkedBlockingQueue<Command> repQueue = new LinkedBlockingQueue<>(2 << 16);
    @Value("${replication.mode:master}")
    private String replicationMode;
    private List<Jedis> slaverHostList = Lists.newArrayList();
    @Value("${slaver.of:127.0.0.1:16379}")
    private String slaverOf;
    @Autowired
    private PubSubHelper pubSubHelper;
    public static final String repKey = "replication";
    private boolean isInitSubscriber;
    private Thread asyncTask = new Thread(new Runnable() {
        @Override
        public void run() {
            while (true) {
                Command command = null;
                try {
                    command = repQueue.take();
                    byte[] data = SerializationUtil.serialize(command);
                    ByteBuf byteBuf = Unpooled.buffer(4 + data.length);
                    byteBuf.writeInt(data.length);
                    byteBuf.writeBytes(data);
                    publish(byteBuf);
                } catch (Exception e) {
                    log.error("", e);
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException e1) {
                        e1.printStackTrace();
                    }
                    if (command != null) {
                        repQueue.offer(command);
                    }
                }
            }
        }
    });

    private Thread syncTask = new Thread(new Runnable() {
        @Override
        public void run() {
            try {
                Thread.sleep(5000L);
                System.err.println(String.format("sync data start"));
                //connect to master
                HostAndPort master = HostAndPort.fromString(slaverOf);
                Jedis jedis = new Jedis(master.getHostText(), master.getPort(), 60 * 1000, 60 * 1000);
                //send slaveof cmd
                String resp = jedis.slaveof(System.getProperty("server.host"), Integer.parseInt(System.getProperty("server.port")));
                System.err.println(String.format("sync data end,state=%s", resp));
                jedis.close();
            } catch (Exception e) {
                log.error("", e);
            }
        }
    });

    @PostConstruct
    public void initThread() {
        if (replicationMode.equals("master")) {
            asyncTask.setName("asyncReplicationTask");
            asyncTask.start();
        } else {
            syncTask.setName("syncReplicationTask");
            syncTask.start();
        }
    }

    public interface Wrapper {
        Reply execute(Command command, ChannelHandlerContext ch) throws RedisException;
    }

    public void init(RedisCommandProcessor rss) {
        this.rs = rss;
        final Class<? extends RedisCommandProcessor> aClass = rs.getClass();
        for (final Method method : aClass.getMethods()) {
            final Class<?>[] parameterTypes = method.getParameterTypes();
            final String mName = method.getName();
            methods.put(new BytesKey(mName.getBytes()), new Wrapper() {
                @Override
                public Reply execute(Command command, ChannelHandlerContext ch) {
                    Object[] objects = new Object[parameterTypes.length];
                    long start = System.currentTimeMillis();
                    try {
                        command.toArguments(objects, parameterTypes);
                        //check param length
                        if (command.getObjects().length - 1 < parameterTypes.length) {
                            throw new RedisException("wrong number of arguments for '" + mName + "' command");
                        }
                        rs.setChannelHandlerContext(ch);
                        if (ch != null && rs.hasOpenTx() && command.isInternal()) {
                            return rs.handlerTxOp(command);
                        } else {
                            replication(mName, ch, command);
                            return (Reply) method.invoke(rs, objects);
                        }
                    } catch (Exception e) {
                        log.error("", e);
                        if (e instanceof InvocationTargetException)
                            return new ErrorReply("ERR " + ((InvocationTargetException) e).getTargetException().getMessage());
                        else
                            return new ErrorReply("ERR " + e.getMessage());
                    } finally {
                        log.info("method {},cost {}ms", method.getName(), (System.currentTimeMillis() - start));
                    }
                }

            });
        }
    }

    public Reply handlerEvent(ChannelHandlerContext ctx, Command msg) throws RedisException {
        byte[] name = msg.getName();

        for (int i = 0; i < name.length; i++) {
            byte b = name[i];
            if (b >= 'A' && b <= 'Z') {
                name[i] = (byte) (b + LOWER_DIFF);
            }
        }
        Wrapper wrapper = methods.get(new BytesKey(name));
        Reply reply;
        if (wrapper == null) {
            reply = new ErrorReply("unknown command '" + new String(name, Charsets.US_ASCII) + "'");
            //if started tx,then invoker discard method and setErrorMsg
            if (rs.hasOpenTx()) {
                rs.discard();
                rs.setTXError();
            }
        } else {
            reply = wrapper.execute(msg, ctx);
        }
        return reply;
    }

    boolean isWriteCMD(String cmd) {
        return cmd.matches("set|lpush|rpush|expire|hset|select");
    }

    void replication(String mName, ChannelHandlerContext ch, Command command) {
        if (replicationMode.equals("master")) {
            if (isWriteCMD(mName)) {
                repQueue.offer(command);
            }
        } else {
            if (mName.equals("publish") && !isInitSubscriber) {
                System.err.println("register subscriber ok");
                pubSubHelper.regSubscriber(ch, repKey.getBytes());
                isInitSubscriber = true;
            }
        }
    }

    public void startAsyncReplication(String slaverHost) {
        HostAndPort hostAndPort = HostAndPort.fromString(slaverHost);
        Jedis jedis = new Jedis(hostAndPort.getHostText(), hostAndPort.getPort(), 60 * 1000, 60 * 1000);
        jedis.ping();
        slaverHostList.add(jedis);
    }

    public void publish(ByteBuf byteBuf) {
        for (int i = 0; i < slaverHostList.size(); i++) {
            Jedis jedis = slaverHostList.get(i);
            try {
                jedis.publish(repKey.getBytes(), byteBuf.array());
            } catch (Exception e) {
                log.error("", e);
                slaverHostList.remove(jedis);
            }
        }
    }
}
