package com.liroam.archfixture.bad;

import com.liroam.pricing.mock.archfixture.FakeMockFxSource;

/** 违例夹具：业务代码直接引用非供应商模块的 mock 实现。 */
public class UsesMockFxSource {
    FakeMockFxSource source = new FakeMockFxSource();
}
