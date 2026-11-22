<div align="center">
  <img src="https://lilishop-oss.oss-cn-beijing.aliyuncs.com/4c864e133c2944efad1f7282ac8a3b9e.png" width="80" alt="Lilishop Logo" />
  <h1>Lilishop(PickMall) 开源商城系统 - 后端服务 (Java 21 / Spring Boot 4)</h1>

  <p>
    <a href="https://github.com/lilishop/lilishop"><img src="https://img.shields.io/github/stars/lilishop/lilishop.svg?style=social&logo=github" alt="GitHub Stars" /></a>
    <a href="https://gitee.com/beijing_hongye_huicheng/lilishop"><img src="https://gitee.com/beijing_hongye_huicheng/lilishop/badge/star.svg?theme=dark" alt="Gitee Stars" /></a>
    <a href="./LICENSE"><img src="https://img.shields.io/badge/license-Apache--2.0-blue.svg" alt="License" /></a>
    <a href="https://openjdk.org/projects/jdk/21/"><img src="https://img.shields.io/badge/Java-21-orange.svg" alt="Java 21" /></a>
    <a href="https://spring.io/projects/spring-boot"><img src="https://img.shields.io/badge/Spring%20Boot-4.0.5-brightgreen.svg" alt="Spring Boot 4.0.5" /></a>
  </p>

  <p>
    <a href="https://pickmall.cn">🏠 官方网站</a> ·
    <a href="https://bdwx2tfwwt.feishu.cn/wiki/space/7675179368632617952?ccm_open_type=lark_wiki_spaceLink&open_tab_from=wiki_home">📖 产品文档</a> ·
    <a href="#2-在线演示">💻 在线演示</a> ·
    <a href="#7-社区与支持">💬 微信交流群</a>
  </p>

  <p>
    <b>简体中文</b> | <a href="./README.en.md">English</a>
  </p>
</div>

> ⭐ **如果觉得项目不错，请点击右上角 `Star` 收藏支持！您的 Star 是我们持续开源迭代的最大动力！**

**LILISHOP** 是基于 Spring Boot / Vue 3 / uni-app 开发的开源商城系统，支持 B2B2C 多商户商城、微信小程序商城、H5 商城、APP 商城、直播电商、分销返佣、秒杀活动。

### ✨ 为什么选择 Lilishop
- 🟢 **真 Apache-2.0 协议**：支持免费商用与私有化闭源二开，无版权后顾之忧。
- 🚀 **Java 21 + Spring Boot 4 全新栈**：后端采用 `Spring Boot 4.0.5 + Java 21 + MyBatis-Plus 3.5`，前端采用 `Vue 3.5 + uni-app`，技术栈紧跟主流生态。
- 🧩 **全端生产级完整闭环**：覆盖 **平台端、商家端、PC 端、H5、微信小程序、APP、IM 客服端**，内置商品、订单、售后、多商家结算、秒杀拼团、二级分销、直播、多维统计报表等完整电商业务。

---

## 1. 项目简介

**Lilishop** 是一款功能完善的 B2B2C 多商户商城系统，采用前后端分离架构，全端代码开源。  
后端基于 **Spring Boot 4.0.5**（Java 21）构建，具备高内聚、低耦合的特性，按角色职责拆分为独立的 API 服务模块，支持独立部署与弹性扩展；前端覆盖 PC、管理后台、H5、小程序和 APP，基于 **Vue 3** 和 **uni-app** 开发。

本仓库（`lilishop`）为 **Java 后端服务源码主仓库**。

- **官方网站**: <https://pickmall.cn>
- **官方文档**: [产品文档](https://bdwx2tfwwt.feishu.cn/wiki/space/7675179368632617952?ccm_open_type=lark_wiki_spaceLink&open_tab_from=wiki_home)
- **Gitee 仓库**: <https://gitee.com/beijing_hongye_huicheng>
- **GitHub 主仓库**: <https://github.com/lilishop/lilishop>

---

## 2. 在线演示

> **注意**: 演示站手机验证码统一为 `111111`。演示环境部署于 `master` 分支。

| 演示站点 | 访问地址 | 演示账号 |
| :--- | :--- | :--- |
| **平台运营管理端 (`manager`)** | <https://admin-b2b2c.pickmall.cn> | 账号：`admin` / 密码：`123456` |
| **商家店铺管理端 (`seller`)** | <https://store-b2b2c.pickmall.cn> | 账号：`13011111111` / 密码：`111111` |
| **商城 PC 买家端 (`buyer`)** | <https://pc-b2b2c.pickmall.cn> | 可使用手机号验证码 `111111` 登录 |
| **移动端 H5 (`uni-app`)** | <https://m-b2b2c.pickmall.cn> | 可使用手机号验证码 `111111` 登录 |

| 微信小程序 | 移动端 H5（[点击直接访问](https://m-b2b2c.pickmall.cn)） |
| :---: | :---: |
| <img src="https://static.pickmall.cn/images/h5-qrcode.png" width="180" alt="微信小程序体验码" /> | <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/h5-qrcode.png" width="180" alt="移动端 H5 体验二维码" /> |
| 微信扫码体验小程序 | 手机扫码或访问 <https://m-b2b2c.pickmall.cn> |

### 移动端界面预览

| 商城首页 | 商品列表 | 商品详情 | 填写订单 | 个人中心 |
| :---: | :---: | :---: | :---: | :---: |
| <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/image.png" width="160" alt="商城首页" /> | <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/image3.png" width="160" alt="商品列表" /> | <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/image4.png" width="160" alt="商品详情" /> | <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/image5.png" width="160" alt="填写订单" /> | <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/image2.png" width="160" alt="个人中心" /> |

---

## 3. 核心特性与技术栈

### 核心特性
- **全端覆盖**: 一套后端服务支撑 PC、H5、微信小程序、Android / iOS APP、商家端与平台运营端。
- **多商家入驻 (B2B2C)**: 支持自营与多商户入驻并存，涵盖店铺审核、商品审核、拆单发货、促销活动与店铺结算。
- **API 服务解耦**: 按业务端拆分为 `buyer-api`、`manager-api`、`im-api`，共用 `framework` 领域层，既可轻量单体式部署，也可按模块独立横向扩展。
- **丰富营销与交易形态**: 支持限时秒杀、多人拼团、优惠券、满减、积分商城、砍价、二级分销、购物卡、卡密商品、预约服务商品及微信小程序直播。
- **多云适配与开箱即用**: 内置阿里云 OSS、腾讯云 COS、华为云 OBS、MinIO 多云对象存储切换，集成微信支付 V3、支付宝、快递 100 等全链路三方能力。

### 后端技术栈
| 类别 | 技术选型 | 说明 |
| :--- | :--- | :--- |
| 基础运行环境 | `Java 21` | 采用现代 LTS 版本，高性能与语言特性支持 |
| 核心框架 | `Spring Boot 4.0.5` | 核心 Web、AOP、事务与自动装配底座 |
| 安全与认证 | `Spring Security` + `JWT (jjwt 0.13)` | 多角色（买家/商家/管理员）无状态认证与权限控制 |
| ORM 与数据库 | `MyBatis-Plus 3.5.15` + `Druid` + `MySQL 8.x` | 数据持久化、分页、连接池监控 |
| 缓存与分布式协调 | `Redis` + `Redisson 3.52` | 分布式缓存、分布式锁、限流与延时任务队列 |
| 任务调度 | `Quartz` | 商品定时上下架、促销状态流转、统计汇总等定时任务 |
| 即时通讯与推送 | `WebSocket` + `Eclipse Paho MQTT` | 在线客服消息与实时通知推送 |
| 接口文档 | `SpringDoc OpenAPI 3 (3.0.3)` | 自动化 RESTful API 接口文档 |
| 对象存储 | `Aliyun OSS` / `Tencent COS` / `Huawei OBS` / `MinIO` | 可插拔多云文件与图片存储方案 |
| 支付与三方集成 | `WeChatPay V3` / `Alipay SDK` / `快递100` / `阿里云短信` | 支付收银、退款、物流轨迹查询與短信通知 |

---

## 4. 目录结构

```text
lilishop
├── framework/                # 【公共核心与领域模块】业务实体、Mapper、Service、基础设施封装
│   └── src/main/java/cn/lili/
│       ├── cache/            # Redis / Redisson 缓存与分布式锁封装
│       ├── common/           # 通用异常、统一响应、安全认证、加密与工具类
│       ├── config/           # 全局配置类（线程池、WebMVC、序列化等）
│       ├── mybatis/          # MyBatis-Plus 审计填充、分页与基础基类
│       ├── trigger/          # 延时任务与事件触发器
│       └── modules/          # 核心电商业务领域模块
│           ├── goods/        # 商品、分类、品牌、规格、参数、评价、商品分组
│           ├── order/        # 购物车、交易收银、订单履约、拆单发货、售后维权、发票
│           ├── member/       # 会员体系、等级、积分、收货地址、足迹与收藏
│           ├── store/        # 商家入驻、店铺设置、运费模板、结算单
│           ├── promotion/    # 秒杀、拼团、优惠券、满减、积分商品、砍价、直播
│           ├── distribution/ # 二级分销员、分销商品与佣金结算
│           ├── appointment/  # 预约服务商品模块
│           ├── payment/      # 微信支付 V3、支付宝、预存款钱包收银台封装
│           ├── wallet/       # 会员预存款钱包、购物卡、提现与流水
│           ├── finance/      # 财务对账与发票管理
│           ├── statistics/   # 营业、商品、会员、营销、分销统计与经营报表
│           ├── page/         # PC 与移动端可视化楼层装修、专题页与导航设置
│           ├── im/           # 在线客服会话与消息领域模型
│           ├── permission/   # 平台管理员、部门、角色与菜单权限
│           └── system/       # 系统设置、地区、敏词、定时任务、OSS/短信/物流插件
├── buyer-api/                # 【买家端 API 服务】面向 PC / H5 / 小程序 / APP 的接口层
├── manager-api/              # 【平台管理端 & 商家店铺端 API 服务】面向运营后台与商家后台的接口层
├── im-api/                   # 【在线客服 API 服务】面向 IM Web 与移动端的即时通讯服务
├── DB/                       # 数据库初始化 SQL 脚本
└── update-sql/               # 历次版本增量升级 SQL 脚本
```

---

## 5. 配套源码仓库

本仓库为 **Java 后端服务源码**（`lilishop`）。完整系统由以下四个核心仓库组成：

| 仓库名称 | 职责说明 | 仓库地址 |
| :--- | :--- | :--- |
| **`lilishop`** | 后端服务源码（Spring Boot 4 / Java 21） | [Gitee](https://gitee.com/beijing_hongye_huicheng/lilishop) · [GitHub](https://github.com/lilishop/lilishop) |
| **`lilishop-ui`** | PC 商城 / 商家端 / 运营管理端 / IM Web 前端 | [Gitee](https://gitee.com/beijing_hongye_huicheng/lilishop-ui) |
| **`lilishop-uniapp`** | 买家端 H5 / 微信小程序 / Android & iOS APP | [Gitee](https://gitee.com/beijing_hongye_huicheng/lilishop-uniapp) |
| **`docker`** | 容器化部署编排、镜像与数据库脚本 | [Gitee](https://gitee.com/beijing_hongye_huicheng/docker) · [MySQL 脚本](https://gitee.com/beijing_hongye_huicheng/docker/tree/master/init/mysql) |

---

## 6. 开源与授权

本项目遵循 [Apache-2.0](./LICENSE) 开源协议，版权归 **北京宏业汇成科技有限公司** 所有。允许个人及企业免费用于学习、研究、修改和商业用途（需保留原始版权和许可声明）。

---

## 7. 社区与支持

我们欢迎任何形式的交流与贡献。在提问前，请先查阅 [产品文档](https://bdwx2tfwwt.feishu.cn/wiki/space/7675179368632617952?ccm_open_type=lark_wiki_spaceLink&open_tab_from=wiki_home)，并参考 [《提问的智慧》](https://github.com/ryanhanwu/How-To-Ask-Questions-The-Smart-Way/blob/master/README-zh_CN.md) 以便高效沟通。

- **[在线客服](https://work.weixin.qq.com/kfid/kfc4d8dc24a73c15f44)**
- **微信交流群**:  
  <img src="https://lilishop-wechat.oss-cn-beijing.aliyuncs.com/wechat.jpg" width="200" alt="微信群" />

---

## 8. 升级计划

### ✅ 已完成功能


| 时间 | 功能模块            | 完成内容                                          |
| :--- |:----------------|:----------------------------------------------|
| 2026-11-14 | 单品立减 | 支持设置商品每件立减，买家购买时直接享受优惠。 |
| 2026-11-14 | 多件优惠 | 支持满件减钱，买家可混选活动商品凑件享优惠。 |
| 2026-11-14 | 每日免费抽奖 | 支持每日免费抽奖，可抽取积分或优惠券并查看抽奖记录。 |
| 2026-11-14 | 积分兑换优惠券 | 支持配置积分兑券活动，买家可用积分兑换优惠券并查看兑换记录。 |
| 2026-11-12 | 优惠券礼包 | 支持多张优惠券打包领取，展示礼包可省金额。 |
| 2026-11-12 | 出库单 / 库存流水 | 支持商家管理出库单、查询商品库存变动。 |
| 2026-11-06 | 提货卡 | 支持设置可提货商品、制卡发卡、兑换核销和使用记录查询。 |
| 2026-11-05 | 付费会员          | 支持付费开通和续费会员，享受指定商家及商品折扣。 |
| 2026-11-04 | 客户等级体系 / 客户权益 | 客户可通过任务积累经验值，升级并享受对应权益。 |
| 2026-11-04 | 小程序订单管理       | 支持查询小程序订单、设置微信订单自动上报。 |
| 2026-11-04 | 立减优惠券         | 支持发放和领取立减券，下单时抵扣商品金额。 |
| 2026-11-04 | 商品定时上下架       | 支持设置商品自动上架、下架时间并管理执行计划。 |
| 2026-11-04 | 微信小程序原生能力 | 接入微信地址导入、头像选择和昵称填写。                           |
| 2026-11-02 | 消息模板 | 支持按业务场景配置站内信、短信及微信消息通知。 |
| 2026-10-30 | PC 页面装修 | 支持可视化装修 PC 页面，设置楼层高度和图片热区。 |
| 2026-10-29 | 定时任务管理        | 支持在运营后台配置和维护定时任务。 |
| 2026-10-29 | 敏感词管理 | 支持管理敏感词，并按业务场景开启或关闭过滤。 |
| 2026-10-29 | PC 店铺搜索 | 支持按商品或店铺搜索，查看店铺列表。 |
| 2026-10-22 | 分类设置 | 支持选择分类页模板，自定义商品展示字段并预览效果。 |
| 2026-10-20 | 移动端页面装修 | 支持可视化装修移动端页面，配置商品、优惠券、店铺等楼层。 |
| 2026-10-09 | 主题色管理         | 支持自定义商城主题色。 |
| 2026-10-02 | 预约服务商品        | 支持管理预约服务商品、预约下单和查看订单详情。 |
| 2026-08-05 | 二级分销          | 支持二级分销关系管理、节点配置及分销员中心。 |
| 2026-08-03 | 卡密商品          | 支持卡密商品销售、订单发货和卡池管理。 |
| 2026-07-23 | 素材库管理 | 支持按分类和目录管理素材。 |
| 2026-07-20 | 经营统计报表 | 支持查看店铺业绩、商品销售趋势及订单明细报表。 |
| 2026-07-13 | 腾讯云直播         | 支持直播、直播间聊天和直播商品管理。 |
| 2026-06-30 | 移动端技术升级 | 升级 Vue 3 与 uView Plus，适配组件和页面。                |
| 2026-06-29 | 电子面单发货 | 支持快递鸟电子面单发货，自动关联运单号。 |
| 2026-06-05 | 后端与管理端框架升级 | Spring Boot 4、管理端 Vue 3 与 Element Plus 升级。    |
| 2026-05-22 | 礼品卡           | 支持礼品卡选择、绑定、激活和列表管理。 |
| 2026-05-20 | 微信提现 | 支持微信提现、转账状态查询和重试，规则说明配置待补充。 |
| 2026-04-16 | 置顶评论          | 支持管理商品评价、置顶指定评论。 |
| 2026-04-13 | 专用发票（发票上传）    | 支持上传专用发票、填写发票信息和查看发票详情。 |
| 2026-04-01 | 商品虚拟销量        | 支持按商品规格单独或批量设置虚拟销量。 |
| 2026-03-30 | 商品分组          | 支持创建商品分组并关联商品。 |
| 2026-03-24 | 会员分组          | 支持创建和管理会员分组。 |

### 🔜 近期规划中
- 🔜 微信小店对接
- 🔜 抖店对接
- 🔜 微信小程序原生重构与体验对齐
- 🔜 OpenAPI 开放平台与开发者中心
