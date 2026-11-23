import { MOCK_MENU_PLACEHOLDER } from '@brand/decorate-mock.js';

// 首页装修接口无数据或失败时的兜底页，与库里平台首页（V202611261300 / V202611261330 迁移后）一致。
export const LIROAM_PAGE_DOCUMENT = {
  "list": [
    {
      "id": "row_1793267493325_ezo48",
      "rowStyle": {
        "gap": 0,
        "width": "1920",
        "height": "",
        "margin": [
          0,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793267493325_ku3n9",
          "icon": "Menu",
          "name": "快捷栏",
          "type": "topBar",
          "options": {
            "fontSize": 12,
            "loginUrl": "/login",
            "loginText": "登录",
            "textColor": "#4b5563",
            "logoutText": "退出",
            "rightMenus": [
              {
                "url": "/home/UserInfo",
                "title": "基本信息"
              },
              {
                "url": "/home/MyOrder",
                "title": "我的订单"
              },
              {
                "url": "/home/AccountSafe",
                "title": "账户安全"
              },
              {
                "url": "/home/Msg",
                "title": "消息"
              }
            ],
            "registerUrl": "/signUp",
            "welcomeText": "欢迎来到轻游",
            "registerText": "免费注册",
            "userCenterUrl": "/order-list"
          },
          "componentStyle": {
            "height": 36,
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              0,
              0,
              0,
              0
            ],
            "padding": [
              0,
              0,
              0,
              0
            ],
            "textColor": "#64748b",
            "widthRatio": "100%",
            "backgroundColor": "#f8fafc",
            "backgroundImage": ""
          }
        }
      ]
    },
    {
      "id": "row_1793267497509_t3n4h",
      "rowStyle": {
        "gap": 0,
        "width": 1920,
        "height": 120,
        "margin": [
          0,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793267497509_3igj2",
          "icon": "Search",
          "name": "搜索头部",
          "type": "searchHeader",
          "options": {
            "logoUrl": "",
            "cartText": "我的购物车",
            "hotWords": [
              "日本",
              "韩国",
              "泰国",
              "欧洲多国",
              "美国"
            ],
            "logoLink": "/",
            "showCart": true,
            "cartBgColor": "#ffffff",
            "placeholder": "请输入商品名称",
            "showHotWords": true,
            "cartTextColor": "#111827",
            "cartBadgeColor": "#e05644",
            "cartBorderColor": "#e2e8f0",
            "showSearchTypes": true,
            "storePlaceholder": "请输入店铺名称",
            "defaultSearchType": "goods"
          },
          "componentStyle": {
            "height": 90,
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              0,
              0,
              0,
              0
            ],
            "padding": [
              16,
              0,
              16,
              0
            ],
            "widthRatio": "100%",
            "backgroundColor": "#ffffff",
            "backgroundImage": ""
          }
        }
      ]
    },
    {
      "id": "row_1793267517026_plue6",
      "rowStyle": {
        "gap": 0,
        "width": "1400",
        "height": "420",
        "margin": [
          0,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793267508694_y5bsh",
          "icon": "Fold",
          "name": "商品分类",
          "type": "categoryMegaMenu",
          "options": {
            "title": "商品分类",
            "source": "system",
            "fontSize": 14,
            "textColor": "#111927",
            "categories": MOCK_MENU_PLACEHOLDER,
            "activeColor": "#0B7A75"
          },
          "componentStyle": {
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              8,
              8,
              8,
              8
            ],
            "padding": [
              0,
              0,
              0,
              0
            ],
            "widthRatio": "16.67%",
            "backgroundColor": "#ffffff",
            "backgroundImage": ""
          }
        },
        {
          "id": "cmp_1793267517026_l7o7t",
          "icon": "PictureRounded",
          "name": "轮播图",
          "type": "carousel",
          "options": {
            "list": [
              {
                "img": "/liroam/decorate/banner-1.svg",
                "url": "/goodsList",
                "title": "出境上网，出发前备好"
              },
              {
                "img": "/liroam/decorate/banner-2.svg",
                "url": "/goodsList",
                "title": "eSIM 付款后在线发码"
              },
              {
                "img": "/liroam/decorate/banner-3.svg",
                "url": "/home/MyTravelCards",
                "title": "一人多卡，按卡管理"
              }
            ],
            "effect": "fade",
            "height": 420,
            "fitMode": "contain",
            "autoPlay": true,
            "interval": 4000,
            "showArrow": true,
            "aspectRatio": "2.5:1",
            "showIndicator": true
          },
          "componentStyle": {
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              8,
              8,
              8,
              8
            ],
            "padding": [
              0,
              0,
              0,
              0
            ],
            "widthRatio": "66.67%",
            "backgroundColor": "#ffffff",
            "backgroundImage": ""
          }
        },
        {
          "id": "cmp_1793267529376_s645r",
          "icon": "User",
          "name": "用户权益",
          "type": "userBenefits",
          "options": {
            "avatar": "",
            "cartUrl": "/cart",
            "cartText": "购物车",
            "loginUrl": "/login",
            "orderUrl": "/member/order",
            "services": [
              {
                "url": "/member/favorites",
                "icon": "Star",
                "name": "收藏"
              },
              {
                "url": "/member/footprint",
                "icon": "Clock",
                "name": "足迹"
              },
              {
                "url": "/couponCenter",
                "icon": "Present",
                "name": "红包"
              },
              {
                "url": "/article",
                "icon": "Service",
                "name": "客服"
              }
            ],
            "showCart": true,
            "subtitle": "登录后查看专属权益",
            "badgeText": "请登录",
            "couponUrl": "/member/coupon",
            "loginText": "登录",
            "orderText": "订单",
            "couponText": "优惠券",
            "showOrders": true,
            "registerUrl": "/signUp",
            "showCoupons": true,
            "welcomeText": "欢迎来到轻游",
            "registerText": "注册"
          },
          "componentStyle": {
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              8,
              8,
              8,
              8
            ],
            "padding": [
              16,
              14,
              16,
              14
            ],
            "widthRatio": "16.67%",
            "backgroundColor": "#ffffff",
            "backgroundImage": ""
          }
        }
      ]
    },
    {
      "id": "row_1793267546877_j629a",
      "rowStyle": {
        "gap": 0,
        "width": "1400",
        "height": "230",
        "margin": [
          10,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793267546877_v2psi",
          "icon": "Files",
          "name": "活动卡组",
          "type": "promoGroup",
          "options": {
            "cards": [
              {
                "url": "/seckill",
                "bgImg": "/liroam/decorate/promo-1.svg",
                "title": "限时特价",
                "subtitle": "热门套餐限时优惠"
              },
              {
                "url": "/goodsList",
                "bgImg": "/liroam/decorate/promo-2.svg",
                "title": "新上架",
                "subtitle": "新开通目的地套餐"
              },
              {
                "url": "/home/MyTravelCards",
                "bgImg": "/liroam/decorate/promo-3.svg",
                "title": "续费加包",
                "subtitle": "原卡在线办理，无需换卡"
              },
              {
                "url": "/coupon",
                "bgImg": "/liroam/decorate/promo-4.svg",
                "title": "领券中心",
                "subtitle": "下单前先领券"
              }
            ],
            "columns": 4
          },
          "componentStyle": {
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              12,
              12,
              12,
              12
            ],
            "padding": [
              0,
              0,
              0,
              0
            ],
            "widthRatio": "100%",
            "backgroundColor": "transparent",
            "backgroundImage": ""
          }
        }
      ]
    },
    {
      "id": "row_1793268768622_krg93",
      "rowStyle": {
        "gap": 0,
        "width": "1400",
        "height": "60",
        "margin": [
          0,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793268768622_c9uif",
          "icon": "Edit",
          "name": "标题栏",
          "type": "titleBar",
          "options": {
            "align": "left",
            "moreUrl": "/goodsList",
            "moreText": "查看更多 >",
            "showMore": true,
            "subtitle": "按目的地和天数选购",
            "mainTitle": "热门套餐"
          },
          "componentStyle": {
            "margin": [
              10,
              0,
              5,
              0
            ],
            "radius": [
              0,
              0,
              0,
              0
            ],
            "padding": [
              16,
              8,
              12,
              8
            ],
            "widthRatio": "100%",
            "backgroundColor": "transparent",
            "backgroundImage": ""
          }
        }
      ]
    },
    {
      "id": "row_1793267810637_03mmz",
      "rowStyle": {
        "gap": 0,
        "width": "1400",
        "height": "650",
        "margin": [
          0,
          0,
          0,
          0
        ],
        "radius": [
          0,
          0,
          0,
          0
        ],
        "padding": [
          0,
          0,
          0,
          0
        ]
      },
      "components": [
        {
          "id": "cmp_1793267810637_mbkox",
          "icon": "Goods",
          "name": "商品列表",
          "type": "goodsList",
          "options": {
            "goods": [],
            "columns": 6,
            "showTags": true,
            "sortType": "default",
            "showPrice": true,
            "showTitle": true,
            "categoryId": "",
            "dataSource": "category",
            "goodsCount": 12,
            "categoryIds": [],
            "showCartBtn": true,
            "categoryName": "",
            "showOriginalPrice": true
          },
          "componentStyle": {
            "margin": [
              0,
              0,
              0,
              0
            ],
            "radius": [
              0,
              0,
              0,
              0
            ],
            "padding": [
              0,
              0,
              0,
              0
            ],
            "widthRatio": "100%",
            "backgroundColor": "transparent",
            "backgroundImage": ""
          }
        }
      ]
    }
  ],
  "version": 2,
  "pageStyle": {
    "backgroundColor": "#f8fafc"
  }
};
