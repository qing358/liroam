// 设计稿公共壳：页头、手机标签栏、页脚与图标，按 <body data-*> 注入，避免每页重复。
// 图标为示意线稿，实现时用 lucide 同名图标（@iconify-json/lucide，本地打包）。
(function () {
  const LOGO = '../../../../logo/svg/liroam-horizontal-ink.svg';
  const P = {
    search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.5-3.5"/>',
    left: '<path d="m15 18-6-6 6-6"/>',
    right: '<path d="m9 18 6-6-6-6"/>',
    home: '<path d="M3 10.5 12 3l9 7.5V21H3z"/><path d="M9 21v-6h6v6"/>',
    sim: '<path d="M7 3h7l5 5v13H7z"/><rect x="10" y="11" width="6" height="7" rx="1"/>',
    help: '<circle cx="12" cy="12" r="9"/><path d="M9.5 9.5a2.5 2.5 0 1 1 3.5 2.3c-.6.3-1 .9-1 1.6V14"/><path d="M12 17h.01"/>',
    user: '<circle cx="12" cy="8" r="4"/><path d="M4 21c1.5-4 4.5-6 8-6s6.5 2 8 6"/>',
    globe: '<circle cx="12" cy="12" r="9"/><path d="M3 12h18M12 3c3 3.5 3 14.5 0 18M12 3c-3 3.5-3 14.5 0 18"/>',
    pin: '<path d="M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z"/><circle cx="12" cy="9.5" r="2.5"/>',
    qr: '<rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><path d="M14 14h3v3h-3zM20 14v.01M14 20h.01M17 20h4v-3"/>',
    copy: '<rect x="8" y="8" width="13" height="13" rx="2"/><path d="M16 8V5a2 2 0 0 0-2-2H5a2 2 0 0 0-2 2v9a2 2 0 0 0 2 2h3"/>',
    check: '<path d="m5 12 5 5 9-10"/>',
    clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
    box: '<path d="m3 7 9-4 9 4v10l-9 4-9-4z"/><path d="m3 7 9 4 9-4M12 11v10"/>',
    phone: '<rect x="6" y="2" width="12" height="20" rx="2"/><path d="M11 18h2"/>',
    chat: '<path d="M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"/>',
    info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v5M12 8h.01"/>',
    bolt: '<path d="M13 2 4 14h7l-1 8 9-12h-7z"/>',
    shield: '<path d="M12 3 4 6v6c0 5 3.5 8 8 9 4.5-1 8-4 8-9V6z"/><path d="m9 12 2 2 4-4"/>',
    plus: '<path d="M12 5v14M5 12h14"/>',
    order: '<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M9 8h6M9 12h6M9 16h4"/>',
    wallet: '<rect x="3" y="6" width="18" height="14" rx="2"/><path d="M3 10h18M16 15h2"/>',
    link: '<path d="M10 14a5 5 0 0 0 7 0l3-3a5 5 0 0 0-7-7l-1 1"/><path d="M14 10a5 5 0 0 0-7 0l-3 3a5 5 0 0 0 7 7l1-1"/>',
    bell: '<path d="M6 16V11a6 6 0 0 1 12 0v5l2 2H4z"/><path d="M10 21h4"/>',
    lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
    truck: '<path d="M3 6h11v10H3zM14 10h4l3 3v3h-7"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="18" r="2"/>',
    refund: '<path d="M4 4v6h6"/><path d="M4 10a8 8 0 1 1 2 6"/>',
    filter: '<path d="M4 5h16M7 12h10M10 19h4"/>',
    x: '<path d="M6 6l12 12M18 6 6 18"/>',
  };
  const icon = (n, cls = '') => `<svg class="icon ${cls}" viewBox="0 0 24 24">${P[n] || ''}</svg>`;
  document.querySelectorAll('i[data-i]').forEach((el) => { el.outerHTML = icon(el.dataset.i, el.className); });

  const b = document.body.dataset;
  const nav = [['index', '选目的地', '01-home.html'], ['cards', '我的卡', '09-cards.html'], ['orders', '订单', '13-orders.html'], ['help', '帮助中心', '15-help.html']];
  const header = document.createElement('header');
  header.className = 'site-header';
  const mobileLeft = b.back
    ? `<a class="back" href="${b.back}">${icon('left')}</a><div class="title">${b.title || ''}</div>`
    : `<a class="logo hide-pc" href="01-home.html"><img src="${LOGO}" alt="轻游"></a><div class="grow hide-pc"></div>`;
  header.innerHTML = `<div class="container bar">
    ${mobileLeft}
    <a class="logo hide-m" href="01-home.html"><img src="${LOGO}" alt="轻游"></a>
    <nav>${nav.map(([k, t, h]) => `<a href="${h}" class="${b.nav === k ? 'on' : ''}">${t}</a>`).join('')}</nav>
    <div class="hide-m">${b.login === 'no' ? '<a class="btn btn-primary btn-sm" href="05-login.html">登录 / 注册</a>' : `<a class="row" href="18-account.html" style="color:var(--text)">${icon('user')} 138****5678</a>`}</div>
    ${b.back ? '' : `<a class="hide-pc" href="15-help.html" style="color:var(--text)">${icon('help')}</a>`}
  </div>`;
  document.body.prepend(header);

  if (b.tab) {
    const tabs = [['index', '选卡', 'globe', '01-home.html'], ['cards', '我的卡', 'sim', '09-cards.html'], ['help', '帮助', 'help', '15-help.html'], ['me', '我的', 'user', '18-account.html']];
    const bar = document.createElement('nav');
    bar.className = 'tabbar';
    bar.innerHTML = tabs.map(([k, t, i, h]) => `<a href="${h}" class="${b.tab === k ? 'on' : ''}">${icon(i)}${t}</a>`).join('');
    document.body.append(bar);
  }

  if (b.footer !== 'no') {
    const f = document.createElement('footer');
    f.className = 'footer';
    f.innerHTML = `<div class="container">
      <div class="cols4">
        <div><img src="../../../../logo/svg/liroam-horizontal-reverse.svg" alt="轻游" style="height:30px"><p style="margin-top:12px">按目的地选购出境旅行通信卡，eSIM 付款后在线发码，每张卡用量随时可查。</p></div>
        <div><h4>选购指南</h4><a>如何选套餐</a><a>eSIM 与实体卡</a><a>设备兼容说明</a><a>常见问题</a></div>
        <div><h4>开卡与使用</h4><a>激活码使用</a><a>开卡进度查询</a><a>实名认证</a><a>境外上网设置</a></div>
        <div><h4>续费与加包</h4><a>续费说明</a><a>加包说明</a><a>用量查询</a><a>到期提醒</a></div>
        <div><h4>售后服务</h4><a>退款说明</a><a>实体卡配送</a><a>渠道订单认领</a><a>联系客服</a></div>
      </div>
      <div class="legal"><span>想象智能（济南）有限公司 · 用户协议 · 隐私政策</span><span class="anno-note">备案号与经营许可证号上线前按证照填写</span></div>
    </div>`;
    document.body.append(f);
  }
})();
