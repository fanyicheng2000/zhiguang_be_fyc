package com.tongji.relation.processor;

import com.tongji.relation.event.RelationEvent;
import com.tongji.relation.mapper.RelationMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import com.tongji.counter.service.UserCounterService;
import org.springframework.stereotype.Service;
import java.time.Duration;

/**
 * 关系事件处理器。
 * 职责：对 FollowCreated/FollowCanceled 事件落库更新粉丝表，维护关注/粉丝 ZSet 缓存与 TTL，并更新用户维度计数（SDS）。
 * [方案二改造] 所有操作均为天然幂等，无需外部去重键。
 */
@Service
public class RelationEventProcessor {
    private final RelationMapper mapper;
    private final StringRedisTemplate redis;
    private final UserCounterService userCounterService;

    public RelationEventProcessor(RelationMapper mapper, StringRedisTemplate redis, UserCounterService userCounterService) {
        this.mapper = mapper;
        this.redis = redis;
        this.userCounterService = userCounterService;
    }

    /**
     * 处理关系事件：入库、更新缓存、刷新计数。
     * 所有操作均天然幂等，可安全重试。
     * @param evt 关系事件
     */
    public void process(RelationEvent evt) {
        // [方案二改造] 旧：去重键门卫（所有操作已天然幂等，去重键不再必要，已注释）
        // String dk = "dedup:rel:" + evt.type() + ":" + evt.fromUserId() + ":" + evt.toUserId() + ":" + (evt.id() == null ? "0" : String.valueOf(evt.id()));
        // Boolean first = redis.opsForValue().setIfAbsent(dk, "1", Duration.ofMinutes(10));
        // if (first == null || !first) { return; }

        if ("FollowCreated".equals(evt.type())) {
            // 异步插入粉丝表
            mapper.insertFollower(evt.id(), evt.toUserId(), evt.fromUserId(), 1);
            long now = System.currentTimeMillis();

            // 更新关注表与粉丝表缓存：ZSet 按时间分数维护最近项，设置短 TTL 减少陈旧数据
            redis.opsForZSet().add("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()), now);
            redis.opsForZSet().add("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()), now);
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2));
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2));

            // [方案二改造] 旧：增量语义（非幂等，已注释）
            // userCounterService.incrementFollowings(evt.fromUserId(), 1);
            // userCounterService.incrementFollowers(evt.toUserId(), 1);

            // [方案二改造] 新：覆写语义——从 DB COUNT 后直接 SET，天然幂等
            long followings = mapper.countFollowingActive(evt.fromUserId());
            long followers  = mapper.countFollowerActive(evt.toUserId());
            userCounterService.setFollowings(evt.fromUserId(), followings);
            userCounterService.setFollowers(evt.toUserId(), followers);
        } else if ("FollowCanceled".equals(evt.type())) {
            mapper.cancelFollower(evt.toUserId(), evt.fromUserId());

            // 更新关注表与粉丝表缓存：移除 ZSet 项并刷新 TTL
            redis.opsForZSet().remove("uf:flws:" + evt.fromUserId(), String.valueOf(evt.toUserId()));
            redis.opsForZSet().remove("uf:fans:" + evt.toUserId(), String.valueOf(evt.fromUserId()));
            redis.expire("uf:flws:" + evt.fromUserId(), Duration.ofHours(2));
            redis.expire("uf:fans:" + evt.toUserId(), Duration.ofHours(2));

            // [方案二改造] 旧：增量语义（非幂等，已注释）
            // userCounterService.incrementFollowings(evt.fromUserId(), -1);
            // userCounterService.incrementFollowers(evt.toUserId(), -1);

            // [方案二改造] 新：覆写语义——从 DB COUNT 后直接 SET，天然幂等
            long followings = mapper.countFollowingActive(evt.fromUserId());
            long followers  = mapper.countFollowerActive(evt.toUserId());
            userCounterService.setFollowings(evt.fromUserId(), followings);
            userCounterService.setFollowers(evt.toUserId(), followers);
        }
    }
}
