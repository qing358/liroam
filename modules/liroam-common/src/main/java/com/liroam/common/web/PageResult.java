package com.liroam.common.web;

import java.util.List;

/** 分页结果，字段名与壳 MyBatis-Plus {@code IPage} 序列化一致（records/total/current/size），后台表格组件可直接用。 */
public record PageResult<T>(List<T> records, long total, int current, int size) {

    public PageResult {
        records = List.copyOf(records);
    }

    /** 页码从 1 开始；size 限 1..100。 */
    public static int offset(int current, int size) {
        return (Math.max(current, 1) - 1) * clampSize(size);
    }

    public static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }
}
