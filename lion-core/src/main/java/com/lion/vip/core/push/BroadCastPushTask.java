package com.lion.vip.core.push;

import com.lion.vip.api.common.Condition;
import com.lion.vip.api.connection.Connection;
import com.lion.vip.api.connection.SessionContext;
import com.lion.vip.api.message.Message;
import com.lion.vip.api.spi.push.IPushMessage;
import com.lion.vip.common.condition.AwaysPassCondition;
import com.lion.vip.common.message.PushMessage;
import com.lion.vip.common.qps.FlowControl;
import com.lion.vip.common.qps.OverFlowException;
import com.lion.vip.core.LionServer;
import com.lion.vip.core.router.LocalRouter;
import com.lion.vip.tools.common.TimeLine;
import com.lion.vip.tools.log.Logs;
import io.netty.channel.ChannelFuture;

import java.util.*;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 广播推送任务
 */
public final class BroadCastPushTask implements PushTask {
    private final long begin = System.currentTimeMillis();
    private final AtomicInteger finishTasks = new AtomicInteger(0);
    private final TimeLine timeLine = new TimeLine();
    private final Set<String> successUserIds = new HashSet<>(1024);
    private final FlowControl flowControl;
    private final IPushMessage message;
    private final Condition condition;
    private final LionServer lionServer;

    //使用Iterator，记录任务遍历到的位置；因为有流控，一次任务可能会被分批发送，而且还有在推送过程中上下线的用户
    private final Iterator<Map.Entry<String, Map<Integer, LocalRouter>>> iterator;

    public BroadCastPushTask(LionServer lionServer, IPushMessage message, FlowControl flowControl) {
        this.flowControl = flowControl;
        this.message = message;
        this.lionServer = lionServer;
        this.condition = message.getCondition();

        //从lionServer中拿到 路由中心-->本地路由管理器-->路由表
        this.iterator = lionServer.getRouterCenter().getLocalRouterManager().routers().entrySet().iterator();
        this.timeLine.begin("push-center-begin");
    }

    @Override
    public void run() {
        flowControl.reset();
        boolean done = broadcast();
        if (done) {    //广播结束
            if (finishTasks.addAndGet(flowControl.total()) == 0) {
                report();
            }
        } else {    //如果没有结束，则延时进行下次任务
            lionServer.getPushCenter().delayTask(flowControl.getDelay(), this);
        }
        flowControl.end(successUserIds.toArray(new String[successUserIds.size()]));
    }

    /**
     * 广播
     *
     * @return
     */
    private boolean broadcast() {
        try {
            iterator.forEachRemaining(entry -> {
                String userId = entry.getKey();
                entry.getValue().forEach((clientType, router) -> {
                    Connection connection = router.getRouteValue();
                    //1.条件检测
                    if (checkCondition(condition, connection)) {
                        //2.连接是否在已连接的状态
                        if (connection.isConnected()) {
                            //3.检测channel是否是可写的：检测TCP缓冲区是否已满且写队列超过最高阀值
                            if (connection.getChannel().isWritable()) {
                                PushMessage.build(connection)
                                        .setContent(message.getContent())
                                        .send(future -> operationComplete(future, userId));

                                //4.检测qps，是否超过流控限制，如果超过则结束当前循环，直接进入catch
                                if (!flowControl.checkQps()) {
                                    throw new OverFlowException(false);
                                }
                            }
                        } else {    //2.如果连接失效，先删除本地失效的路由，再查远程路由，看用户是否登录其他机器
                            Logs.PUSH.warn("[Broadcast] find router in local but connection disconnect, message={}, conn={}", message, connection);
                            //删除已经失效的路由
                            lionServer.getRouterCenter().getLocalRouterManager().unregister(userId, clientType);
                        }
                    }
                });
            });
        } catch (OverFlowException e) {
            //超出最大限制，或者遍历完毕，结束广播
            return e.isOverMaxLimit() || !iterator.hasNext();
        }
        return !iterator.hasNext();    //循环完毕，结束广播
    }

    /**
     * 条件检测：
     *
     * @param condition
     * @param connection
     * @return
     */
    private boolean checkCondition(Condition condition, Connection connection) {
        if (condition == AwaysPassCondition.I) {    //如果condition为AwayPassCondition类型的，直接通过
            return true;
        }

        SessionContext sessionContext = connection.getSessionContext();
        Map<String, Object> env = new HashMap<>();

        env.put("userId", sessionContext.userId);
        env.put("tags", sessionContext.tags);
        env.put("clientVersion", sessionContext.clientVersion);
        env.put("osName", sessionContext.osName);
        env.put("osVersion", sessionContext.osVersion);

        return condition.test(env);
    }

    /**
     * 完整执行操作的剩余操作
     *
     * @param future
     * @param userId
     */
    private void operationComplete(ChannelFuture future, String userId) {
        if (future.isSuccess()) {    //推送成功
            successUserIds.add(userId);
            Logs.PUSH.info("[Broadcast] push message to client success, userId={}, message={}", message.getUserId(), message);
        } else {    //推送失败
            Logs.PUSH.warn("[Broadcast] push message to client failure, userId={}, message={}", message.getUserId(), message);
        }

        if (finishTasks.decrementAndGet() == 0) {    //如果任务执行完毕，通知发送方
            report();
        }
    }

    /**
     * 通知发送方：广播推送完毕
     */
    private void report() {
        Logs.PUSH.info("[Broadcast] task finished, cost={}, message={}", (System.currentTimeMillis() - begin), message);
        //通知发送方，广播推送完毕
        lionServer.getPushCenter().getPushListener().onBroadcastComplete(message, timeLine.end().getTimePoints());
    }

    @Override
    public ScheduledExecutorService getExecutor() {
        return ((Message) message).getConnection().getChannel().eventLoop();
    }
}
