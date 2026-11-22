package com.liroam.common.ops;

import java.time.Instant;
import java.util.List;

/** 各模块按自己的表与阈值出告警；{@link OpsAlerts} 汇总。只读查询，不改状态。 */
public interface OpsAlertSource {

    List<OpsAlert> check(Instant now);
}
