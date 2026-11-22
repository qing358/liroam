package com.liroam.archfixture.bad;

import cn.lili.common.exception.ServiceException;

/** 违例夹具：壳桥接层之外引用 cn.lili。 */
public class LeaksLilishop {
    public RuntimeException fail() {
        return new ServiceException("x");
    }
}
