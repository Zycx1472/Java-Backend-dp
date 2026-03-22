package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.concurrent.ListenableFutureCallback;

import javax.annotation.Resource;
import java.util.Arrays;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 */
@Slf4j
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    /*此部分为异步秒杀的演进方案，对比了同步下单与异步队列的性能差异。*/

    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private RabbitTemplate rabbitTemplate;

    @Resource
    private IVoucherOrderService voucherOrderService;

    /*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    @PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{
        @Override
        public void run(){
            while(true){
                try {
                    VoucherOrder voucherOrder = orderTasks.take();
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常");
                }
            }
        }
    }
    */

    @RabbitListener(queues = "seckill.order.queue")
    public void listenSeckillOrder(String message) {
        // 1. 解析消息
        VoucherOrder voucherOrder = JSONUtil.toBean(message, VoucherOrder.class);
        // 2. 依然调用你写好的 handleVoucherOrder
        handleVoucherOrder(voucherOrder);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder){
        // 1.获取用户
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock lock = redissonClient.getLock("lock:order:" + userId);
        //  3.尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock) {
            // 获取锁失败，返回错误或重试
            log.error("不允许重复下单");
            return;
        }
        try {
            voucherOrderService.createVoucherOrder(voucherOrder);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        List<String> keys = Arrays.asList(
                "seckill:stock:" + voucherId,
                "seckill:order:" + voucherId
        );
        // 1.执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                userId.toString()
        );
        // 2.判断结果是为0
        int r = result.intValue();
        if (r != 0) {
            // 2.1.不为0，代表没有购买资格
            return Result.fail(r==1 ? "库存不足":"不能重复下单");
        }
        // 2.2.为0，有购买资格，把下单信息保存到阻塞队列
        long orderId = redisIdWorker.nextId("order");
        // 2.3.信息封装
        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        // 3. 创建 CorrelationData 并绑定异步回调
        CorrelationData cd = new CorrelationData(String.valueOf(orderId));
        cd.getFuture().addCallback(new ListenableFutureCallback<CorrelationData.Confirm>() {
            @Override
            public void onSuccess(CorrelationData.Confirm result) {
                if (result.isAck()) {
                    // MQ 已经签收并持久化
                    log.debug("秒杀消息投递成功, 订单ID: {}", orderId);
                } else {
                    // MQ 拒绝签收（极端情况，如磁盘满或内存溢出）
                    log.error("秒杀消息投递失败 (nack), 原因: {}, 订单ID: {}", result.getReason(), orderId);
                }
            }

            @Override
            public void onFailure(Throwable ex) {
                // Future 本身执行异常（几乎不会发生）
                log.error("消息发送过程发生异常, 订单ID: {}", orderId, ex);
            }
        });
        // 2.4 放入阻塞队列
        //orderTasks.add(voucherOrder);
        // 2.4 发送到 RabbitMQ
        rabbitTemplate.convertAndSend(
                "seckill.exchange",
                "seckill.order",
                JSONUtil.toJsonStr(voucherOrder),
                cd
        );
        // 4.返回订单id
        return Result.ok(0);
    }

    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 5.一人一单
        Long userId = voucherOrder.getUserId();
        Long vId = voucherOrder.getVoucherId();
        // 5.1.查询订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_Id", vId)
                .count();
        // 5.2.判断是否存在
        if (count > 0){
            // 用户已经购买过了
            log.error("用户已经购买过一次");
            return ;
        }

        // 6.扣减库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", vId)
                .gt("stock", 0)
                .update();

        if (!success) {
            return;
        }

        save(voucherOrder);
    }

    /*@Override
    public Result seckillVoucher(Long voucherId) {
        // 1.查询优惠劵
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        // 2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            // 尚未开始
           return Result.fail("秒杀尚未开始");
        }
        // 3.判断秒杀是否已经结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        // 4.判断库存是否充足
        if (voucher.getStock() < 1){
            return Result.fail("库存不足");
        }

        // 5.返回订单id
        Long userId = UserHolder.getUser().getId();
        // 创建锁对象
        //SimpleRedisLock lock = new SimpleRedisLock("order:" + userId, stringRedisTemplate);
        RLock lock = redissonClient.getLock("lock:order" + userId);
        // 尝试获取锁
        boolean isLock = lock.tryLock();
        if(!isLock) {
            // 获取锁失败，返回错误或重试
            return Result.fail("不允许重复下单");
        }
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } catch (IllegalStateException e) {
            throw new RuntimeException(e);
        } finally {
            lock.unlock();
        }

    }

    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        // 5.一人一单
        Long userId = UserHolder.getUser().getId();
        // 5.1.查询订单
        int count = query()
                .eq("user_id", userId)
                .eq("voucher_Id", voucherId)
                .count();
        // 5.2.判断是否存在
        if (count > 0){
            // 用户已经购买过了
            return Result.fail("用户已经购买过一次");
        }

        // 6.扣减库存 (6和7是加transaction的主要原因)
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        // 7.创建订单 (6和7是加transaction的主要原因)
        VoucherOrder voucherOrder = new VoucherOrder();
        // 7.1.订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        // 7.2.用户id
        voucherOrder.setUserId(userId);
        // 7.3.代金券id
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        return Result.ok(orderId);
    }*/

}
