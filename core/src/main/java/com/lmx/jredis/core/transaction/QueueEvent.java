package com.lmx.jredis.core.transaction;

import lombok.Builder;
import lombok.Data;

/**
 * Created by limingxin on 2017/12/20.
 */
@Data
@Builder
public class QueueEvent {
    private redis.netty4.Command command;
}
