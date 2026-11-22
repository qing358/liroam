package com.liroam.supplier.archfixture;

import org.springframework.jdbc.core.simple.JdbcClient;

/** 违例夹具：供应商侧代码直接访问数据库。 */
public class AdapterWithStorage {
    JdbcClient jdbc;
}
