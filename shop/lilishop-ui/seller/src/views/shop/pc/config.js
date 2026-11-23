import { MOCK_BRAND_WALL, MOCK_CAROUSEL, MOCK_CATEGORY_NAV, MOCK_CHANNELS, MOCK_FLOOR, MOCK_FLOOR_BANNER, MOCK_GOODS, MOCK_GOODS_CARD, MOCK_HOT_ZONE, MOCK_MENU_PLACEHOLDER, MOCK_PROMO_CARDS, MOCK_VIDEO_COVER } from '@brand/decorate-mock.js';
/**

 * 店铺 PC 楼层装修组件配置清单（与运营后台保持相同布局）

 * 共 4 大分类，19 个组件

 */



const ALL_COMPONENT_GROUPS = [

  {

    key: 'structure',

    title: '结构',

    description: '页头、页脚与基础框架',

    icon: 'House'

  },

  {

    key: 'navigation',

    title: '导航',

    description: '类目、频道和入口',

    icon: 'Compass'

  },

  {

    key: 'media',

    title: '广告',

    description: '轮播、热区与活动图',

    icon: 'Picture'

  },

  {

    key: 'commerce',

    title: '商品',

    description: '商品列表、楼层和推荐',

    icon: 'ShoppingBag'

  },

  {

    key: 'marketing',

    title: '营销',

    description: '用户权益、优惠券等促销能力',

    icon: 'Ticket'

  },

  {

    key: 'content',

    title: '内容',

    description: '标题、文章和公告',

    icon: 'Document'

  }

];



const ALL_COMPONENT_DEFINITIONS = [

  // 1. 结构 (3个)









  // 2. 导航 (3个)

  {

    type: 'categoryNav',
    defaultRowHeight: 48,
    name: '类目导航',
    group: 'navigation',
    icon: 'List',
    description: '横向主导航栏条目、分类图标与高亮角标',
    defaultOptions: {
      showAllCategory: false,
      allCategoryText: '全部分类',
      allCategoryIcon: '',
      textColor: '#334155',
      activeColor: '#e4572e',
      navItems: MOCK_CATEGORY_NAV
    },
    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: '#ffffff',
      height: 42,
      radius: [0, 0, 0, 0],
      padding: [0, 0, 0, 0]
    }
  },

  {

    type: 'channelGrid',
    defaultRowHeight: '',
    name: '频道宫格',
    group: 'navigation',
    icon: 'Grid',
    description: '4~8 列频道入口与图文卡片',
    defaultOptions: {
      columns: 4,
      imageRadius: 10,
      gap: 12,
      items: MOCK_CHANNELS
    },
    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: 'transparent',
      radius: [0, 0, 0, 0],
      padding: [10, 0, 10, 0]
    }
  },

  {

    type: 'categoryMegaMenu',
    defaultRowHeight: 320,
    name: '商品分类',
    group: 'navigation',
    icon: 'Fold',
    description: '垂直分类菜单与多级展开弹层',
    defaultOptions: {
      title: '商品分类',
      source: 'system',
      textColor: '#111927',
      fontSize: 14,
      activeColor: '#0b7a75',
      categories: MOCK_MENU_PLACEHOLDER
    },
    defaultComponentStyle: {
      widthRatio: '16.67%',
      backgroundColor: '#ffffff',
      radius: [8, 8, 8, 8],
      padding: [0, 0, 0, 0]
    }
  },



  // 3. 广告 (6个)

  {

    type: 'carousel',
    defaultRowHeight: 320,
    name: '轮播图',
    group: 'media',
    icon: 'PictureRounded',
    description: '首页大图焦点轮播',
    defaultOptions: {
      autoPlay: true,
      interval: 4000,
      showArrow: true,
      effect: 'fade',
      showIndicator: true,
      fitMode: 'contain',
      aspectRatio: '2.5:1',
      height:  420,
      list: MOCK_CAROUSEL
    },
    defaultComponentStyle: {
      widthRatio: '66.67%',
      backgroundColor: '#ffffff',
      radius: [8, 8, 8, 8],
      padding: [0, 0, 0, 0]
    }
  },

  {

    type: 'brandWall',
    defaultRowHeight: '',
    name: '品牌图',
    group: 'media',
    icon: 'Medal',
    description: '精选品牌与活动推广图片列表',
    defaultOptions: {
      columns: 4,
      gap: 12,
      items: MOCK_BRAND_WALL
    },
    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: 'transparent',
      radius: [0, 0, 0, 0],
      padding: [10, 0, 10, 0]
    }
  },

  {
    type: 'hotZone',
    defaultRowHeight: 750,
    name: '热区图',
    group: 'media',
    icon: 'Aim',
    description: '一张大图框选多个跳转热区',
    defaultOptions: {
      imageUrl: MOCK_HOT_ZONE.imageUrl,
      zones: MOCK_HOT_ZONE.zones
    },
    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: 'transparent',
      radius: [8, 8, 8, 8],
      padding: [10, 0, 10, 0]
    }
  },

  {

    type: 'freeLayout',
    defaultRowHeight: 450,

    name: '自由布局',

    group: 'media',

    icon: 'FullScreen',

    description: '在固定高度容器内绝对定位自由拖放组件',

    defaultOptions: {

      height: 450,

      elements: []

    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12]

    }

  },

  {

    type: 'promoGroup',
    defaultRowHeight: 220,

    name: '活动卡组',

    group: 'media',

    icon: 'Files',

    description: '2~4 列自适应大促卡片矩阵',

    defaultOptions: {

      columns: 3,

      cards: MOCK_PROMO_CARDS

    },

    defaultComponentStyle: {

      backgroundColor: 'transparent',

      radius: [12, 12, 12, 12]

    }

  },

  {

    type: 'video',
    defaultRowHeight: 450,
    name: '视频',
    group: 'media',
    icon: 'VideoCamera',
    description: '品牌宣传与商品视频播放',
    defaultOptions: {
      videoUrl: '',
      height: 450,
      coverUrl: MOCK_VIDEO_COVER,
      url: '',
      autoPlay: false,
      loop: false,
      controls: true
    },

    defaultComponentStyle: {

      radius: [12, 12, 12, 12]

    }

  },



  // 4. 商品 (8个)

  {

    type: 'goodsList',
    defaultRowHeight: '',

    name: '商品列表',

    group: 'commerce',

    icon: 'Goods',

    description: '基础网格商品流，支持手动选品与分类加载',

    defaultOptions: {

      dataSource: 'manual', // 'manual' | 'category'

      categoryIds: [],

      goodsCount: 12,

      goods: MOCK_GOODS,

      columns: 6,

      showTitle: true,

      showPrice: true,

      showOriginalPrice: true,

      showCartBtn: true,

      showTags: true

    },

    defaultComponentStyle: {

      backgroundColor: 'transparent'

    }

  },

  {

    type: 'gridFloor',
    defaultRowHeight: '',

    name: '宫格楼层',

    group: 'commerce',

    icon: 'Grid',

    description: '百货大楼层：1F标题 + 左侧立体海报 + 右侧商品网格',

    defaultOptions: {

      showFloorTitle: true,

      floorNum: '1F',

      title: MOCK_FLOOR.title,

      subtitle: MOCK_FLOOR.subtitle,

      subCategories: MOCK_FLOOR.subCategories,

      moreUrl: '/goods',

      showPoster: true,

      posterImg: MOCK_FLOOR_BANNER,

      posterTitle: MOCK_FLOOR.posterTitle,

      posterSub: MOCK_FLOOR.posterSub,

      posterUrl: '/goods',

      posterTags: MOCK_FLOOR.posterTags,

      columns: 4,

      goodsCount: 8,

      goods: MOCK_GOODS.slice(0, 8)

    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12],

      padding: [20, 20, 20, 20]

    }

  },

  {

    type: 'goodsGrid',
    defaultRowHeight: '',
    name: '商品宫格',
    group: 'commerce',
    icon: 'Menu',
    description: '严谨商品矩阵：带网格分割线商品单元格',
    defaultOptions: {
      title: '热门套餐榜',
      subtitle: '按目的地挑选',
      showBorder: true,
      showMoreLink: true,
      moreText: '查看全部',
      columns: 5,
      goods: MOCK_GOODS.slice(0, 10)
    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12],

      padding: [16, 16, 16, 16]

    }

  },

  {

    type: 'goodsCard',
    defaultRowHeight: 360,

    name: '商品卡片',

    group: 'commerce',

    icon: 'Postcard',

    description: '精选单品爆款特写大卡片',

    defaultOptions: {
      type: 'goodsCard',
      showSales: false,
      goodsId: '1',
      goodsInfo: MOCK_GOODS[0],
      customTitle: MOCK_GOODS_CARD.customTitle,
      customDesc: MOCK_GOODS_CARD.customDesc,
      badge: MOCK_GOODS_CARD.badge,
      goods: MOCK_GOODS.slice(0, 1)
    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12],

      padding: [24, 24, 24, 24]

    }

  },

  {

    type: 'bannerFloor',
    defaultRowHeight: '',

    name: '横幅楼层',

    group: 'commerce',

    icon: 'Reading',

    description: '左侧海报横幅 + 右侧商品网格',

    defaultOptions: {
      title: '欧洲多国专区',
      subtitle: '一卡多国 · 长途出行',
      bannerImg: MOCK_FLOOR_BANNER,
      bannerUrl: '/goodsList?category=1',
      goodsCount: 6,
      columns: 3,
      goods: MOCK_GOODS.slice(0, 6)
    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12],

      padding: [16, 16, 16, 16]

    }

  },

  {

    type: 'goodsBar',
    defaultRowHeight: '',

    name: '商品条',

    group: 'commerce',

    icon: 'DArrowRight',

    description: '单行横向横滑商品条',

    defaultOptions: {
      title: '本周热卖',
      subtitle: '出发前备好网络',
      columns: 6,
      goods: MOCK_GOODS.slice(0, 6)
    },

    defaultComponentStyle: {

      backgroundColor: '#ffffff',

      radius: [12, 12, 12, 12],

      padding: [16, 16, 16, 16]

    }

  },

  {type: 'channelFloor',
    defaultRowHeight: '',

    name: '频道楼层',

    group: 'commerce',

    icon: 'CollectionTag',

    description: '场景频道：主视觉 + 活动入口 + 精选商品组合',

    defaultOptions: {
      showTitle: true,
      title: '场景频道',
      subtitle: '主视觉 + 商品组合楼层',
      heroImage: MOCK_FLOOR_BANNER,
      heroTitle: '热门目的地',
      heroSubtitle: '按目的地选购旅行通信卡',
      heroButtonText: '进入频道',
      heroHref: '/goods',
      heroStyle: 'gradient',
      promos: [
        { title: '限时特价', desc: '热门套餐限时优惠', tag: '限时', url: '/goods' },
        { title: '新上架', desc: '新开通目的地套餐', tag: '新品', url: '/goods' }
      ],
      goodsCount: 6,
      goods: MOCK_GOODS.slice(0, 6)
    },

    defaultComponentStyle: {
      backgroundColor: '#ffffff',
      radius: [12, 12, 12, 12],
      padding: [20, 20, 20, 20]
    }
  },

  {
    type: 'recommendFeed',
    defaultRowHeight: '',

    name: '推荐商品',

    group: 'commerce',

    icon: 'Star',

    description: '猜你喜欢：精选大卡+瀑布网格推荐流',

    defaultOptions: {
      title: '猜你喜欢',
      subtitle: '真实推荐商品流',
      layoutStyle: 'featured',
      pageSize: 8,
      maxPages: 3,
      backgroundColor: '#f8fafc',
      imageRadius: 10,
      gap: 12,
      goods: MOCK_GOODS.slice(0, 8)
    },

    defaultComponentStyle: {
      backgroundColor: '#f8fafc',
      radius: [12, 12, 12, 12],
      padding: [24, 20, 24, 20]
    }
  },

  // 5. 营销 (2个)





  // 6. 内容 (3个)

  {

    type: 'titleBar',
    defaultRowHeight: 48,

    name: '标题栏',

    group: 'content',

    icon: 'Edit',

    description: '楼层独立大标题与查看更多入口',

    defaultOptions: {

      mainTitle: '楼层标题',

      subtitle: '副标题说明文字',

      align: 'left', // 'left' | 'center'

      showMore: true,

      moreText: '查看更多 >',

      moreUrl: '/'

    },

    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: 'transparent',
      padding: [16, 8, 12, 8]
    }

  },



  {
    type: 'noticeBar',
    defaultRowHeight: 40,

    name: '公告快讯',

    group: 'content',

    icon: 'Bell',

    description: '跑马灯/垂直翻滚公告快讯条',

    defaultOptions: {
      title: '公告快讯',
      showMore: true,
      moreText: '更多',
      moreUrl: '/article',
      items: [
        { tag: '最新', title: '日本、韩国、泰国等热门目的地套餐已上架，可按天数选购', time: '10-28', url: '/article' },
        { tag: '活动', title: '「我的卡」支持在线续费与加包，无需换卡', time: '10-27', url: '/article' },
        { tag: '通知', title: '实名类套餐请在开卡前完成实名认证', time: '10-26', url: '/article' },
        { tag: '热议', title: '出境前如何确认手机支持 eSIM？查看设备兼容说明', time: '10-25', url: '/article' }
      ]
    },

    defaultComponentStyle: {
      widthRatio: '100%',
      backgroundColor: '#ffffff',
      radius: [8, 8, 8, 8],
      padding: [0, 16, 0, 16]
    }
  }
];

export const COMPONENT_DEFINITIONS = ALL_COMPONENT_DEFINITIONS.filter(def => !["topBar","searchHeader","footerNav","userBenefits","couponList","articleContent"].includes(def.type));
export const COMPONENT_GROUPS = ALL_COMPONENT_GROUPS.filter(group => COMPONENT_DEFINITIONS.some(def => def.group === group.key));

export const DEFAULT_ROW_STYLE = {
  width: 1920,
  height: '',
  gap: 0,
  margin: [0, 0, 0, 0],
  padding: [0, 0, 0, 0],
  radius: [0, 0, 0, 0]
};



/**

 * 生成新行

 */

export function createRow(component = null) {
  const rowId = `row_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;
  const components = [];
  let rowHeight = '';

  if (component) {
    if (component.type && !component.componentStyle) {
      // 传入的是组件定义 def
      components.push(createComponent(component));
      if (component.defaultRowHeight !== undefined && component.defaultRowHeight !== null) {
        rowHeight = component.defaultRowHeight;
      }
    } else {
      // 传入的是已有组件实例
      components.push(component);
      const def = COMPONENT_DEFINITIONS.find(d => d.type === component.type);
      if (def?.defaultRowHeight !== undefined && def?.defaultRowHeight !== null) {
        rowHeight = def.defaultRowHeight;
      }
    }
  }

  return {
    id: rowId,
    rowStyle: { ...DEFAULT_ROW_STYLE, height: rowHeight },
    components
  };
}



/**

 * 生成新组件实例

 */

export function createComponent(def) {

  const cmpId = `cmp_${Date.now()}_${Math.random().toString(36).substring(2, 7)}`;

  const style = JSON.parse(JSON.stringify(def.defaultComponentStyle || {}));

  if (!style.margin) style.margin = [0, 0, 0, 0];

  if (!style.padding) style.padding = [0, 0, 0, 0];

  if (!style.radius) style.radius = [0, 0, 0, 0];

  if (!style.widthRatio) style.widthRatio = '100%';

  if (!style.backgroundColor && style.backgroundColor !== 'transparent') style.backgroundColor = '#ffffff';

  if (!style.backgroundImage) style.backgroundImage = '';

  return {

    id: cmpId,

    type: def.type,

    name: def.name,

    icon: def.icon,

    componentStyle: style,

    options: JSON.parse(JSON.stringify(def.defaultOptions || {}))

  };

}
