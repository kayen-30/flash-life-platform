(function () {
  "use strict";

  const API_BASE = "/api";
  const FALLBACK_IMAGES = [
    "/imgs/lifeflash/food-table.jpg",
    "/imgs/lifeflash/sunset-coffee.jpg",
    "/imgs/lifeflash/rainy-night-market.jpg",
    "/imgs/lifeflash/live-music-room.jpg",
    "/imgs/lifeflash/riverside-dinner.jpg",
    "/imgs/lifeflash/studio-weekend.jpg",
    "/imgs/lifeflash/morning-walk.jpg"
  ];
  const FALLBACK_AVATARS = [
    "/imgs/icons/kkjtbcr.jpg",
    "/imgs/icons/user5-icon.png",
    "/imgs/icons/default-icon.png",
    "/imgs/icons/icon1.jpg"
  ];
  const FALLBACK_TYPES = [
    { id: 1, name: "美食", icon: "types/ms.png" },
    { id: 2, name: "咖啡", icon: "types/jsyd.png" },
    { id: 3, name: "酒吧", icon: "types/jiuba.png" },
    { id: 4, name: "娱乐", icon: "types/KTV.png" },
    { id: 5, name: "活动", icon: "types/qzyl.png" },
    { id: 6, name: "丽人", icon: "types/spa.png" }
  ];
  const DEMO_BLOGS = [
    { id: "demo-1", title: "西湖边的落日咖啡，坐到天黑也不想走", name: "阿昊", liked: 128, images: FALLBACK_IMAGES[1] },
    { id: "demo-2", title: "藏在巷子里的小馆子，菜单很短但每道都稳", name: "麦子", liked: 96, images: FALLBACK_IMAGES[0] },
    { id: "demo-3", title: "周末去看一场展，再走到湖边吹风", name: "小野", liked: 76, images: FALLBACK_IMAGES[5] },
    { id: "demo-4", title: "杭州夜生活路线：从晚餐到现场音乐", name: "活动君", liked: 64, images: FALLBACK_IMAGES[2] },
    { id: "demo-5", title: "一家适合朋友聚餐的江景餐厅", name: "林一一", liked: 52, images: FALLBACK_IMAGES[4] },
    { id: "demo-6", title: "城市漫步的第三站，终于找到一间好店", name: "Kumo", liked: 41, images: FALLBACK_IMAGES[6] }
  ];

  const ICON_PATHS = {
    search: "<circle cx='11' cy='11' r='7'></circle><path d='m20 20-4-4'></path>",
    pin: "<path d='M20 10c0 5-8 12-8 12S4 15 4 10a8 8 0 1 1 16 0Z'></path><circle cx='12' cy='10' r='2.5'></circle>",
    bell: "<path d='M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4'></path>",
    bot: "<rect x='4' y='7' width='16' height='13' rx='3'></rect><path d='M12 3v4M8 12h.01M16 12h.01M8 16h8'></path>",
    arrow: "<path d='M5 12h14M13 6l6 6-6 6'></path>",
    heart: "<path d='M20.8 8.7c0 5.4-8.8 10.1-8.8 10.1S3.2 14.1 3.2 8.7A4.7 4.7 0 0 1 12 6.1a4.7 4.7 0 0 1 8.8 2.6Z'></path>",
    bookmark: "<path d='M6 4.5A2.5 2.5 0 0 1 8.5 2h7A2.5 2.5 0 0 1 18 4.5V21l-6-3-6 3Z'></path>",
    plus: "<path d='M12 5v14M5 12h14'></path>",
    close: "<path d='m6 6 12 12M18 6 6 18'></path>",
    send: "<path d='m22 2-7 20-4-9-9-4Z'></path><path d='M22 2 11 13'></path>",
    check: "<path d='m5 12 4 4L19 6'></path>",
    menu: "<path d='M4 6h16M4 12h16M4 18h16'></path>",
    user: "<circle cx='12' cy='8' r='4'></circle><path d='M4 21a8 8 0 0 1 16 0'></path>",
    calendar: "<rect x='3' y='4' width='18' height='17' rx='2'></rect><path d='M16 2v4M8 2v4M3 10h18'></path>",
    image: "<rect x='3' y='4' width='18' height='16' rx='2'></rect><circle cx='8.5' cy='9' r='1.5'></circle><path d='m21 15-5-5L5 20'></path>",
    star: "<path d='m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2-5.6-2.9-5.6 2.9 1.1-6.2L3 9.6l6.2-.9Z'></path>",
    clock: "<circle cx='12' cy='12' r='9'></circle><path d='M12 7v5l3 2'></path>",
    external: "<path d='M14 4h6v6M20 4l-9 9'></path><path d='M18 13v5a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h5'></path>"
  };

  const state = Vue.reactive({
    route: { name: "home", query: {} },
    loading: false,
    error: "",
    types: FALLBACK_TYPES,
    blogs: DEMO_BLOGS,
    shops: [],
    shop: null,
    vouchers: [],
    aiRecommendation: "",
    user: null,
    followCount: 0,
    signCount: 0,
    searchKeyword: "",
    aiOpen: false,
    aiQuestion: "",
    aiSending: false,
    aiConversationId: null,
    loginPhone: "",
    loginCode: "",
    aiMessages: [{ role: "assistant", content: "你好，我是 LifeFlash 城市向导。告诉我你想吃什么、去哪里，或者现在想安排怎样的城市路线。" }],
    toast: [],
    requestId: 0,
    note: null
  });

  const renderedMarkup = Vue.ref("");

  function icon(name, size) {
    return "<svg aria-hidden='true' width='" + (size || 18) + "' height='" + (size || 18) + "' viewBox='0 0 24 24' fill='none' stroke='currentColor' stroke-width='1.8' stroke-linecap='round' stroke-linejoin='round'>" + (ICON_PATHS[name] || ICON_PATHS.star) + "</svg>";
  }
  function escapeHtml(value) {
    return String(value == null ? "" : value).replace(/[&<>'"]/g, function (char) {
      return ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;" })[char];
    });
  }
  function imageUrl(value, index) {
    const raw = Array.isArray(value) ? value[0] : String(value || "").split(",")[0];
    const source = String(raw || "").trim();
    const fallback = FALLBACK_IMAGES[(index || 0) % FALLBACK_IMAGES.length];
    if (!source || source === "null") return fallback;
    if (/^(https?:)?\/\//i.test(source)) return fallback;
    if (/^\/(blogs|types|icons)\//i.test(source)) return "/imgs" + source;
    if (/^imgs\/(blogs|types|icons)\//i.test(source)) return "/" + source;
    if (/^\/imgs\/blogs\//i.test(source)) return fallback;
    return source;
  }
  function avatarUrl(value, index) {
    const raw = Array.isArray(value) ? value[0] : String(value || "").split(",")[0];
    const source = String(raw || "").trim();
    if (/^\/(icons)\//i.test(source)) return "/imgs" + source;
    if (/^\/imgs\/icons\//i.test(source)) return source;
    if (/^imgs\/icons\//i.test(source)) return "/" + source;
    return FALLBACK_AVATARS[(index || 0) % FALLBACK_AVATARS.length];
  }
  function typeIconUrl(value) {
    const source = String(value || "types/ms.png").trim();
    if (/^\/imgs\/types\//i.test(source)) return source;
    if (/^\/types\//i.test(source)) return "/imgs" + source;
    return "/imgs/" + source.replace(/^\/+/, "");
  }
  function money(value) {
    const number = Number(value);
    if (!Number.isFinite(number)) return "--";
    return "¥" + (number > 1000 ? (number / 100).toFixed(0) : number.toFixed(0));
  }
  function score(value) {
    const number = Number(value);
    return Number.isFinite(number) ? (number > 5 ? (number / 10).toFixed(1) : number.toFixed(1)) : "4.6";
  }
  function stars(value) {
    return "★★★★★";
  }
  function qs(name) {
    return new URLSearchParams(window.location.search).get(name) || "";
  }
  function parseHash() {
    const raw = window.location.hash.replace(/^#/, "") || "/";
    const pieces = raw.split("?");
    const path = pieces[0].replace(/^\/+|\/+$/g, "");
    const query = Object.fromEntries(new URLSearchParams(pieces[1] || ""));
    if (!path || path === "home") return { name: "home", query: query };
    if (path === "nearby" || path === "search") return { name: "nearby", query: query };
    if (path === "shop") return { name: "shop", query: query };
    if (path === "note") return { name: "note", query: query };
    if (path === "publish") return { name: "publish", query: query };
    if (path === "profile") return { name: "profile", query: query };
    if (path === "login") return { name: "login", query: query };
    return { name: "home", query: query };
  }
  function go(path) {
    window.location.hash = path;
  }
  function goWithQuery(path, params) {
    const search = new URLSearchParams(params || {}).toString();
    go(path + (search ? "?" + search : ""));
  }
  function isLoggedIn() {
    return Boolean(sessionStorage.getItem("token"));
  }
  function notify(message, type) {
    const id = Date.now() + Math.random();
    state.toast.push({ id: id, message: message, type: type || "" });
    render();
    window.setTimeout(function () {
      state.toast = state.toast.filter(function (item) { return item.id !== id; });
      render();
    }, 3400);
  }
  async function api(path, options) {
    const opts = options || {};
    const headers = Object.assign({}, opts.headers || {});
    const token = sessionStorage.getItem("token");
    if (token) headers.authorization = token;
    if (opts.body && !(opts.body instanceof FormData)) headers["Content-Type"] = "application/json";
    const response = await fetch(API_BASE + path, Object.assign({}, opts, { headers: headers }));
    let payload;
    try { payload = await response.json(); } catch (error) { throw new Error("服务器返回了无法读取的内容"); }
    if (!response.ok || !payload.success) {
      if (response.status === 401) {
        sessionStorage.removeItem("token");
        throw new Error("请先登录");
      }
      throw new Error(payload.errorMsg || "请求失败，请稍后重试");
    }
    return payload.data;
  }
  function jsonBody(value) { return JSON.stringify(value); }

  async function loadHome() {
    const requestId = ++state.requestId;
    state.loading = true;
    render();
    const results = await Promise.allSettled([
      api("/shop-type/list"),
      api("/blog/hot?current=1"),
      api("/shop/search?keyword=&current=1&size=6"),
      isLoggedIn() ? api("/user/me") : Promise.resolve(null)
    ]);
    if (requestId !== state.requestId) return;
    const types = results[0].status === "fulfilled" && Array.isArray(results[0].value) ? results[0].value : [];
    const blogs = results[1].status === "fulfilled" && Array.isArray(results[1].value) ? results[1].value : [];
    const shops = results[2].status === "fulfilled" && Array.isArray(results[2].value) ? results[2].value : [];
    state.types = types.length ? types : FALLBACK_TYPES;
    state.blogs = blogs.length ? blogs : DEMO_BLOGS;
    state.shops = shops;
    state.user = results[3].status === "fulfilled" ? results[3].value : state.user;
    state.loading = false;
    state.error = results.some(function (item) { return item.status === "rejected"; }) && !blogs.length ? "部分内容暂时使用本地占位数据，服务连接后会自动刷新。" : "";
    render();
  }
  async function loadNearby() {
    const requestId = ++state.requestId;
    const keyword = state.route.query.keyword || "";
    state.searchKeyword = keyword;
    state.loading = true;
    render();
    try {
      const typeId = state.route.query.typeId || "";
      const result = await api("/shop/search?keyword=" + encodeURIComponent(keyword) + "&typeId=" + encodeURIComponent(typeId) + "&current=1&size=10");
      if (requestId !== state.requestId) return;
      state.shops = Array.isArray(result) ? result : [];
      state.loading = false;
      state.error = "";
      render();
    } catch (error) {
      if (requestId !== state.requestId) return;
      state.loading = false;
      state.shops = [];
      state.error = error.message;
      render();
    }
  }
  async function loadShop(id) {
    if (!id) { go("nearby"); return; }
    const requestId = ++state.requestId;
    state.loading = true;
    state.shop = null;
    state.vouchers = [];
    render();
    try {
      const values = await Promise.all([api("/shop/" + encodeURIComponent(id)), api("/voucher/list/" + encodeURIComponent(id))]);
      if (requestId !== state.requestId) return;
      state.shop = values[0];
      state.vouchers = Array.isArray(values[1]) ? values[1] : [];
      state.loading = false;
      state.error = "";
      render();
    } catch (error) {
      state.loading = false;
      state.error = error.message;
      render();
    }
  }
  async function loadProfile() {
    if (!isLoggedIn()) { go("login"); return; }
    const requestId = ++state.requestId;
    state.loading = true;
    render();
    const values = await Promise.allSettled([api("/user/me"), api("/follow/count"), api("/user/sign/count"), api("/blog/of/me?current=1")]);
    if (requestId !== state.requestId) return;
    state.user = values[0].status === "fulfilled" ? values[0].value : state.user;
    state.followCount = values[1].status === "fulfilled" ? Number(values[1].value || 0) : 0;
    state.signCount = values[2].status === "fulfilled" ? Number(values[2].value || 0) : 0;
    state.blogs = values[3].status === "fulfilled" && Array.isArray(values[3].value) && values[3].value.length ? values[3].value : DEMO_BLOGS.slice(0, 4);
    state.loading = false;
    state.error = "";
    render();
  }
  async function loadNote(id) {
    if (!id) { go("home"); return; }
    const requestId = ++state.requestId;
    state.loading = true;
    state.error = "";
    render();
    try {
      const note = await api("/blog/" + encodeURIComponent(id));
      if (requestId !== state.requestId) return;
      state.note = note;
      state.loading = false;
      render();
    } catch (error) {
      state.loading = false;
      state.error = error.message;
      render();
    }
  }
  async function loadRoute() {
    state.route = parseHash();
    state.error = "";
    if (state.route.name === "home") return loadHome();
    if (state.route.name === "nearby") return loadNearby();
    if (state.route.name === "shop") return loadShop(state.route.query.id);
    if (state.route.name === "profile") return loadProfile();
    if (state.route.name === "note") return loadNote(state.route.query.id);
    render();
  }

  function renderHeader() {
    const current = state.route.name;
    const searchValue = state.searchKeyword || state.route.query.keyword || "";
    return "<header class='site-header'><div class='header-inner'>" +
      "<a class='brand' href='#/' aria-label='LifeFlash 首页'>Life<span class='flash'>Flash</span><small>CITY NOTES</small></a>" +
      "<form class='search-form' data-form='search'><label class='search-field'>" + icon("search", 19) + "<input name='keyword' value='" + escapeHtml(searchValue) + "' placeholder='搜索美食、咖啡、活动、地点' autocomplete='off'><button class='search-submit' type='submit' aria-label='搜索'>" + icon("arrow", 17) + "</button></label></form>" +
      "<nav class='nav-links' aria-label='主导航'>" +
      navLink("发现", "home", current === "home") + navLink("附近", "nearby", current === "nearby") + navLink("关注", "profile", current === "profile") +
      "</nav><div class='header-actions'><button class='header-icon' data-action='ai-open' aria-label='打开 AI 城市向导'>" + icon("bot", 20) + "</button><button class='header-icon' data-action='profile' aria-label='个人中心'>" + icon("user", 20) + "</button>" +
      (state.user ? "<div class='avatar'><img src='" + escapeHtml(avatarUrl(state.user.icon, 1)) + "' alt=''></div>" : "<button class='button secondary' data-action='login'>登录</button>") +
      "</div></div></header>";
  }
  function navLink(label, target, active) { return "<a class='nav-link" + (active ? " active" : "") + "' href='#/" + target + "'>" + label + "</a>"; }
  function renderShell(content) {
    return renderHeader() + "<main class='page-shell'>" + (state.error ? "<div class='error-bar'><span>" + escapeHtml(state.error) + "</span><button class='text-button' data-action='reload'>重新加载</button></div>" : "") + content + "</main>" + renderAi() + renderToasts();
  }
  function renderHome() {
    const heroImages = state.blogs.slice(0, 4).map(function (blog, index) { return imageUrl(blog.images, index); });
    while (heroImages.length < 4) heroImages.push(FALLBACK_IMAGES[heroImages.length]);
    const types = state.types.slice(0, 8);
    const blogs = state.blogs.slice(0, 6);
    const shops = state.shops.slice(0, 3);
    return "<div class='flash-reel'>" + heroImages.map(function (source) { return "<div class='reel-cell'><img src='" + escapeHtml(source) + "' alt='城市生活照片'></div>"; }).join("") + "<div class='frame-mark tl'></div><div class='frame-mark tr'></div><div class='frame-mark bl'></div><div class='frame-mark br'></div><div class='reel-center'><h1>今日闪现</h1><p>把城市里值得去的地方，留给今天</p><span class='reel-record'><i></i> REC 18:35:42</span></div><span class='reel-label'>HANGZHOU / 06.18</span></div>" +
      "<div class='category-rail'><button class='category-item active' data-action='home-filter'><span class='category-icon'>" + icon("star", 25) + "</span><span class='category-name'>推荐</span></button>" + types.map(function (type, index) { return "<button class='category-item' data-action='type' data-type-id='" + escapeHtml(type.id) + "'><span class='category-icon'><img src='" + escapeHtml(typeIconUrl(type.icon)) + "' alt=''></span><span class='category-name'>" + escapeHtml(type.name) + "</span></button>"; }).join("") + "</div>" +
      "<div class='content-grid'><section><div class='section-heading'><div><p class='eyebrow'>DISCOVER HANGZHOU</p><h2>发现杭州</h2></div><span>每天更新 · 真实体验</span></div><div class='filter-row'><button class='filter-link active'>全部</button><button class='filter-link'>关注</button><button class='filter-link'>最新</button></div><div class='discovery-grid'>" + (state.loading ? Array.from({ length: 6 }).map(function () { return "<div class='skeleton'></div>"; }).join("") : blogs.map(renderBlogCard).join("")) + "</div></section><aside class='side-stack'><section class='side-panel red'><div class='side-heading'><h3>附近闪购</h3><span>更多 ›</span></div><div class='deal-list'>" + (shops.length ? shops.map(renderDeal).join("") : renderDemoDeals()) + "</div></section><section class='side-panel teal'><div class='side-heading'><h3>AI 城市向导</h3><span>随时可问</span></div><div class='ai-intro'><div class='ai-orb'>" + icon("bot", 28) + "</div><div><strong>把问题交给城市向导</strong><p>从路线、店铺到优惠，一句话找到答案</p></div></div><button class='ai-ask' data-action='ai-open'>向我提问 " + icon("arrow", 16) + "</button></section></aside></div>";
  }
  function renderBlogCard(blog, index) {
    const id = blog.id == null ? "" : blog.id;
    const title = blog.title || "城市生活新发现";
    return "<article class='discovery-card'><div class='discovery-image' data-action='note' data-id='" + escapeHtml(id) + "'><img src='" + escapeHtml(imageUrl(blog.images, index)) + "' alt='" + escapeHtml(title) + "'><span class='badge " + (index % 3 === 1 ? "teal" : index % 3 === 2 ? "yellow" : "") + "'>" + (index === 0 ? "TOP 1" : index === 1 ? "新店" : "人气") + "</span><button class='save-button' data-action='note' data-id='" + escapeHtml(id) + "' aria-label='打开笔记'>" + icon("bookmark", 16) + "</button></div><div class='discovery-body'><h3 class='discovery-title'>" + escapeHtml(title) + "</h3><div class='rating-line'><span class='stars'>" + stars() + "</span><span class='muted'>4." + (4 + index % 5) + "</span></div><div class='meta-line'><span>¥" + (35 + index * 17) + "/人</span><span>·</span><span>西湖区</span></div><div class='card-foot'><span class='author'><img src='" + escapeHtml(avatarUrl(blog.icon, index)) + "' alt=''><span>" + escapeHtml(blog.name || "LifeFlash 用户") + "</span></span><button class='like-button" + (blog.isLike ? " is-liked" : "") + "' data-action='like' data-id='" + escapeHtml(id) + "' aria-label='点赞'>" + icon("heart", 15) + "<span>" + Number(blog.liked || 0) + "</span></button></div></div></article>";
  }
  function renderDeal(shop, index) {
    return "<button class='deal-row' data-action='shop' data-id='" + escapeHtml(shop.id || "") + "'><span class='deal-thumb'><img src='" + escapeHtml(imageUrl(shop.images, index + 2)) + "' alt=''></span><span><span class='deal-name'>" + escapeHtml(shop.name || "附近好店") + "</span><span class='deal-sub'>" + escapeHtml(shop.description || "到店优惠 · 现在可用") + "</span><span class='deal-price'><b>" + money(shop.avgPrice || 68) + "</b><small>" + (shop.distance ? shop.distance + "m" : "0." + (6 + index) + "km") + "</small></span></span></button>";
  }
  function renderDemoDeals() {
    return ["很久以前羊肉串（武林店）", "M Stand（杭州嘉里中心店）", "Bar Lotus"].map(function (name, index) { return renderDeal({ id: "", name: name, description: index === 0 ? "100元代金券" : "到店立减券", avgPrice: [78, 25, 68][index], images: FALLBACK_IMAGES[index + 1] }, index); }).join("");
  }
  function renderNearby() {
    const shops = state.shops.length ? state.shops : [];
    return "<div class='page-title-row'><div><p class='eyebrow'>NEARBY RADAR</p><h1 class='page-title'>附近值得去</h1><p class='page-subtitle'>按照距离、口碑和当下状态，找到今天的下一站。</p></div><button class='button secondary' data-action='ai-open'>" + icon("bot", 17) + "问 AI 找店</button></div><div class='search-layout'><section class='search-results'><form data-form='nearby-search' class='search-form'><label class='search-field'>" + icon("search", 18) + "<input name='keyword' value='" + escapeHtml(state.searchKeyword) + "' placeholder='店铺、菜品或商圈'><button class='search-submit' type='submit' aria-label='搜索'>" + icon("arrow", 16) + "</button></label></form><div class='result-toolbar'><button class='chip active'>全部</button><button class='chip'>正在营业</button><button class='chip'>离我最近</button><button class='chip'>有优惠</button></div><div class='result-count'><span>找到 " + (shops.length || 0) + " 个结果</span><span>智能推荐 ˅</span></div><div class='result-list'>" + (state.loading ? "<div class='empty-state'>正在搜索附近店铺…</div>" : shops.length ? shops.map(renderResult).join("") : "<div class='empty-state'><div><strong>暂时没有匹配店铺</strong><span>换一个关键词，或让 AI 帮你描述需求。</span></div></div>") + "</div></section><section class='map-surface'><div class='map-grid'></div><div class='map-water'></div><span class='map-road r1'></span><span class='map-road r2'></span><span class='map-road r3'></span><span class='map-road r4'></span><div class='map-controls'><button class='map-control active'>" + icon("check", 15) + "杭州范围</button><button class='map-control'>" + icon("pin", 15) + "定位</button></div><div class='map-pin p1'><span>餐</span></div><div class='map-pin p2 selected'><span>咖</span></div><div class='map-pin p3'><span>展</span></div><div class='map-pin p4'><span>酒</span></div>" + (shops[0] ? "<div class='map-card'><div class='map-card-row'><img src='" + escapeHtml(imageUrl(shops[0].images, 1)) + "' alt=''><div><h3>" + escapeHtml(shops[0].name || "附近好店") + "</h3><p>★ " + score(shops[0].score) + " · " + escapeHtml(shops[0].area || "西湖区") + "</p><p>人均 " + money(shops[0].avgPrice || 98) + " · 营业中</p></div></div><div class='map-card-actions'><button class='text-button'>路线</button><button class='text-button' data-action='shop' data-id='" + escapeHtml(shops[0].id) + "'>查看详情 " + icon("arrow", 13) + "</button></div></div>" : "") + "</section></div>";
  }
  function renderResult(shop, index) {
    return "<article class='result-item'><span class='distance-node'></span><span class='result-thumb'><img src='" + escapeHtml(imageUrl(shop.images, index + 1)) + "' alt=''></span><span><span class='result-title'><strong>" + escapeHtml(shop.name || "附近好店") + "</strong><span class='distance'>" + (shop.distance ? shop.distance + "m" : (680 + index * 520) + "m") + "</span></span><span class='result-meta'>★ " + score(shop.score) + " · " + escapeHtml(shop.area || "杭州") + " · 人均 " + money(shop.avgPrice || 88) + "</span><span class='result-state'>营业中</span><span class='result-deal'>有到店优惠</span></span></article>";
  }
  function renderShop() {
    if (state.loading || !state.shop) return "<div class='empty-state'><div><strong>正在打开店铺</strong><span>正在读取店铺信息和可用优惠。</span></div></div>";
    const shop = state.shop;
    const images = String(shop.images || "").split(",").filter(Boolean);
    while (images.length < 3) images.push(FALLBACK_IMAGES[images.length]);
    return "<div class='page-title-row'><button class='text-button' data-action='back'>‹ 返回附近</button><span>店铺详情 / " + escapeHtml(shop.name || "") + "</span></div><section class='detail-hero'><div class='detail-gallery'><figure><img src='" + escapeHtml(imageUrl(images[0], 0)) + "' alt=''></figure><figure><img src='" + escapeHtml(imageUrl(images[1], 1)) + "' alt=''></figure><figure><img src='" + escapeHtml(imageUrl(images[2], 2)) + "' alt=''></figure></div><div class='detail-copy'><p class='eyebrow'>LIFEFLASH SELECTED</p><h1>" + escapeHtml(shop.name || "附近好店") + "</h1><p>" + escapeHtml(shop.description || "今天值得去的一家店，真实体验和到店优惠都在这里。") + "</p><div class='detail-rating'><span>" + stars() + "</span><strong>" + score(shop.score) + "</strong><span>" + (shop.comments || 19) + " 条体验</span></div><div class='detail-meta'><span>⌖ " + escapeHtml(shop.address || "杭州市西湖区") + "</span><span>◷ " + escapeHtml(shop.openHours || "10:00 - 22:00") + "</span><span>人均 " + money(shop.avgPrice || 88) + " · " + escapeHtml(shop.area || "西湖区") + "</span></div><div class='detail-actions'><button class='button primary' data-action='ai-recommend'>生成 AI 推荐</button><button class='button ghost' data-action='ai-open'>问城市向导</button></div></div></section><div class='detail-sections'><div><section class='detail-section'><h2>今日可用优惠</h2><div class='voucher-list'>" + (state.vouchers.length ? state.vouchers.filter(isActiveVoucher).map(renderVoucher).join("") : "<div class='empty-state'>今天暂时没有可用优惠。</div>") + "</div></section><section class='detail-section'><h2>网友体验</h2><div class='comment-line'><div class='comment-head'><span>叶小 Q · Lv5</span><span>2 小时前</span></div><p>环境很舒服，适合朋友聚餐。优惠券使用顺利，整体体验比预期更好。</p></div><div class='comment-line'><div class='comment-head'><span>走走停停 · Lv4</span><span>昨天</span></div><p>店员服务很细致，推荐靠窗的位置，周末建议提前到。</p></div></section></div><aside class='side-stack'><section class='side-panel teal'><div class='side-heading'><h3>AI 一句话推荐</h3><span>基于店铺数据</span></div><div id='ai-recommendation' class='ai-recommend'>" + escapeHtml(state.aiRecommendation || "点击生成，结合评分、商圈、人均和营业时间给你一句到店建议。") + "</div></section><section class='side-panel'><div class='side-heading'><h3>到店信息</h3><span>实用提示</span></div><div class='deal-list'><div class='deal-row'><span class='ai-orb'>" + icon("pin", 22) + "</span><span><span class='deal-name'>导航到店</span><span class='deal-sub'>打开地图查看路线</span></span></div><div class='deal-row'><span class='ai-orb'>" + icon("clock", 22) + "</span><span><span class='deal-name'>营业时间</span><span class='deal-sub'>" + escapeHtml(shop.openHours || "10:00 - 22:00") + "</span></span></div></div></section></aside></div>";
  }
  function isActiveVoucher(voucher) { return !voucher.endTime || new Date(voucher.endTime).getTime() > Date.now(); }
  function renderVoucher(voucher) {
    const price = money(voucher.payValue || voucher.actualValue || 0);
    const original = money(voucher.actualValue || 0);
    return "<article class='voucher-row'><div><div class='voucher-title'>" + escapeHtml(voucher.title || "到店代金券") + "</div><div class='voucher-sub'>" + escapeHtml(voucher.subTitle || "到店可用 · 每人限购 1 张") + "</div><div class='voucher-price'>" + price + "<small>原价 " + original + "</small></div></div><div class='voucher-action'><span class='stock'>剩余 " + (voucher.stock == null ? "充足" : voucher.stock + " 张") + "</span><button class='button primary' data-action='seckill' data-id='" + escapeHtml(voucher.id) + "'>限时抢购</button></div></article>";
  }
  function renderProfile() {
    const user = state.user || { nickName: "LifeFlash 用户", icon: FALLBACK_IMAGES[0] };
    return "<div class='page-title-row'><div><p class='eyebrow'>YOUR CITY LOG</p><h1 class='page-title'>我的城市记录</h1><p class='page-subtitle'>收藏、关注和你发布过的每一次发现。</p></div><button class='button primary' data-action='publish'>" + icon("plus", 16) + "发布笔记</button></div><div class='profile-grid'><aside class='profile-sidebar'><img class='profile-avatar' src='" + escapeHtml(avatarUrl(user.icon, 0)) + "' alt=''><h2>" + escapeHtml(user.nickName || "LifeFlash 用户") + "</h2><p>杭州 · 正在记录生活</p><div class='stat-row'><div class='stat'><strong>" + state.followCount + "</strong><span>关注</span></div><div class='stat'><strong>" + (user.fans || 0) + "</strong><span>粉丝</span></div><div class='stat'><strong>" + state.signCount + "</strong><span>连续签到</span></div></div><div class='profile-tools'><button class='tool-link' data-action='sign'>" + icon("calendar", 17) + "今日签到</button><button class='tool-link' data-action='logout'>" + icon("external", 17) + "退出当前账号</button></div></aside><section class='profile-main'><div class='profile-cover'><div><p class='eyebrow'>MY NOTES</p><h1>我发布的探店笔记</h1></div><div class='checkin'><strong>" + state.signCount + " 天</strong><button data-action='sign'>完成今日签到 ›</button></div></div><div class='profile-notes'>" + (state.blogs || []).map(renderBlogCard).join("") + "</div></section></div>";
  }
  function renderNote() {
    if (state.loading || !state.note) return "<div class='empty-state'><div><strong>正在打开笔记</strong><span>正在读取作者和体验内容。</span></div></div>";
    const note = state.note;
    return "<div class='page-title-row'><button class='text-button' data-action='back'>‹ 返回发现</button><span>探店笔记</span></div><div class='note-detail'><article class='note-main'><img class='note-cover' src='" + escapeHtml(imageUrl(note.images, 0)) + "' alt='" + escapeHtml(note.title || "") + "'><div class='note-copy'><p class='eyebrow'>CITY NOTE</p><h1>" + escapeHtml(note.title || "城市生活新发现") + "</h1><p>" + escapeHtml(note.content || "记录一次真实到店体验，给下一个准备出发的人一些参考。") + "</p><div class='card-foot'><span>发布于 " + escapeHtml(String(note.createTime || "今天").slice(0, 10)) + "</span><button class='like-button" + (note.isLike ? " is-liked" : "") + "' data-action='like' data-id='" + escapeHtml(note.id) + "'>" + icon("heart", 17) + " " + Number(note.liked || 0) + "</button></div></div></article><aside class='note-aside'><div class='creator'><span class='creator-main'><img src='" + escapeHtml(avatarUrl(note.icon, 1)) + "' alt=''><span><span class='creator-name'>" + escapeHtml(note.name || "LifeFlash 用户") + "</span><span class='creator-meta'>城市生活记录者</span></span></span><button class='button secondary' data-action='follow' data-id='" + escapeHtml(note.userId) + "'>关注</button></div><section class='comments'><div class='section-heading'><h2>评论</h2><span>共 " + Number(note.comments || 0) + " 条</span></div><div class='comment-line'><div class='comment-head'><span>小满</span><span>刚刚</span></div><p>这家店已经加入周末路线，照片拍得很有氛围。</p></div><div class='comment-line'><div class='comment-head'><span>阿南</span><span>昨天</span></div><p>收藏了，想知道工作日晚上需要排队吗？</p></div></section></aside></div>";
  }
  function renderPublish() {
    if (!isLoggedIn()) return renderLogin("发布笔记前，请先登录 LifeFlash");
    return "<div class='page-title-row'><div><p class='eyebrow'>PUBLISH A NOTE</p><h1 class='page-title'>发布一条城市笔记</h1><p class='page-subtitle'>把真实体验留给下一位准备出发的人。</p></div><button class='text-button' data-action='back'>取消</button></div><div class='editor-layout'><form class='editor-panel' data-form='publish'><div class='field'><label for='publish-title'>标题</label><input id='publish-title' name='title' maxlength='40' placeholder='例如：西湖边的落日咖啡，坐到天黑也不想走' required></div><div class='field'><label for='publish-content'>体验内容</label><textarea id='publish-content' name='content' maxlength='1000' placeholder='写下店铺、路线或这次体验里最值得分享的细节。' required></textarea></div><div class='field'><label>配图</label><label class='upload-zone' for='publish-images'><input id='publish-images' name='images' type='file' accept='image/jpeg,image/png,image/gif' multiple><span>" + icon("image", 28) + "<br><strong>选择图片</strong>，最多 3 张，每张 5MB 以内</span></label><div class='upload-preview' id='upload-preview'></div></div><div class='field'><label for='publish-shop'>关联店铺 ID（可选）</label><input id='publish-shop' name='shopId' inputmode='numeric' placeholder='输入店铺 ID'></div><button class='button primary' type='submit'>发布笔记 " + icon("arrow", 16) + "</button></form><aside class='editor-aside'><section class='editor-tip'><h3>一条好笔记</h3><p>标题说清楚场景，正文保留真实细节，配图从环境、食物和路线各选一张。</p></section><section class='editor-tip'><h3>发布后</h3><p>你的笔记会出现在发现页，也会进入关注你的人的更新流。</p></section></aside></div>";
  }
  function renderLogin(message) {
    return "<div class='auth-page'><section class='auth-visual'><a class='brand' href='#/'>Life<span class='flash'>Flash</span></a><div class='auth-copy'><p class='eyebrow'>YOUR CITY, YOUR FLASH</p><h1>把今天<br>过得值得。</h1><p>发现附近的好店、真实的体验和刚好来得及的优惠。</p></div></section><section class='auth-form-wrap'><form class='auth-form' data-form='login'><p class='eyebrow'>WELCOME BACK</p><h2>登录 LifeFlash</h2><p class='lead'>使用手机号接收验证码，继续你的城市记录。</p>" + (message ? "<div class='error-bar'><span>" + escapeHtml(message) + "</span></div>" : "") + "<div class='field'><label for='login-phone'>手机号</label><input id='login-phone' name='phone' inputmode='tel' maxlength='11' value='" + escapeHtml(state.loginPhone) + "' placeholder='请输入手机号' required></div><div class='field'><label for='login-code'>验证码</label><div class='code-row'><input id='login-code' name='code' inputmode='numeric' maxlength='6' value='" + escapeHtml(state.loginCode) + "' placeholder='请输入验证码' required><button type='button' data-action='send-code'>获取验证码</button></div></div><button class='button primary' type='submit' style='width:100%'>登录 " + icon("arrow", 16) + "</button><p class='auth-switch'>登录即表示同意 LifeFlash 用户服务协议</p></form></section></div>";
  }
  function renderAiMessage(message) {
    const sources = Array.isArray(message.sources) ? message.sources.filter(Boolean) : [];
    const sourceMarkup = message.role === "assistant" && sources.length
      ? "<div class='ai-sources'><span class='ai-sources-label'>参考来源</span>" + sources.map(function (source) { return "<span class='ai-source'>" + escapeHtml(source) + "</span>"; }).join("") + "</div>"
      : "";
    return "<div class='ai-message " + message.role + "'>" + escapeHtml(message.content) + sourceMarkup + "</div>";
  }
  function renderAi() {
    if (!state.aiOpen) return "<button class='ai-launcher' data-action='ai-open'>" + icon("bot", 19) + "AI 城市向导</button>";
    return "<div class='ai-backdrop' data-action='ai-close'></div><aside class='ai-drawer'><div class='ai-head'><strong>" + icon("bot", 20) + "AI 城市向导</strong><button class='ai-close' data-action='ai-close' aria-label='关闭'>" + icon("close", 19) + "</button></div><div class='ai-body' id='ai-body'>" + state.aiMessages.map(renderAiMessage).join("") + "</div><form class='ai-input' data-form='ai'><input name='question' value='" + escapeHtml(state.aiQuestion) + "' placeholder='问问秒杀、优惠券、店铺推荐'><button type='submit' aria-label='发送'>" + icon("send", 17) + "</button></form></aside>";
  }
  function renderToasts() { return "<div class='toast-stack'>" + state.toast.map(function (item) { return "<div class='toast " + (item.type || "") + "'>" + escapeHtml(item.message) + "</div>"; }).join("") + "</div>"; }
  function render() {
    if (!renderedMarkup) return;
    let content;
    if (state.route.name === "login") content = renderLogin("");
    else if (state.route.name === "home") content = renderHome();
    else if (state.route.name === "nearby") content = renderNearby();
    else if (state.route.name === "shop") content = renderShop();
    else if (state.route.name === "note") content = renderNote();
    else if (state.route.name === "publish") content = renderPublish();
    else if (state.route.name === "profile") content = renderProfile();
    renderedMarkup.value = state.route.name === "login" ? content : renderShell(content);
    Vue.nextTick(bindImagePreview);
    if (state.aiOpen) {
      const body = document.getElementById("ai-body");
      if (body) body.scrollTop = body.scrollHeight;
    }
  }
  function bindImagePreview() {
    const input = document.getElementById("publish-images");
    const preview = document.getElementById("upload-preview");
    if (!input || !preview) return;
    input.addEventListener("change", function () {
      preview.innerHTML = Array.from(input.files).slice(0, 3).map(function (file) { return "<img src='" + URL.createObjectURL(file) + "' alt='预览图'>"; }).join("");
    });
  }
  async function submitSearch(form) {
    const value = new FormData(form).get("keyword") || "";
    goWithQuery("/nearby", { keyword: value });
  }
  async function submitLogin(form) {
    const data = Object.fromEntries(new FormData(form).entries());
    state.loginPhone = String(data.phone || "");
    state.loginCode = String(data.code || "");
    try {
      const token = await api("/user/login", { method: "POST", body: jsonBody(data) });
      sessionStorage.setItem("token", token);
      state.loginPhone = "";
      state.loginCode = "";
      notify("登录成功，欢迎回来", "success");
      go("/");
    } catch (error) { notify(error.message, "error"); }
  }
  async function submitAi(form) {
    const data = new FormData(form);
    const question = String(data.get("question") || "").trim();
    if (!question || state.aiSending) return;
    state.aiQuestion = "";
    state.aiMessages.push({ role: "user", content: question });
    state.aiSending = true;
    render();
    try {
      const reply = await api("/ai/customer-service/chat", { method: "POST", body: jsonBody({ message: question, conversationId: state.aiConversationId }) });
      const answer = typeof reply === "string" ? reply : (reply && reply.answer) || "我暂时没有找到答案。";
      if (reply && reply.conversationId) state.aiConversationId = reply.conversationId;
      state.aiMessages.push({ role: "assistant", content: answer, sources: reply && Array.isArray(reply.sources) ? reply.sources : [] });
    } catch (error) { state.aiMessages.push({ role: "assistant", content: error.message }); }
    state.aiSending = false;
    render();
  }
  async function submitPublish(form) {
    const data = new FormData(form);
    const files = Array.from(document.getElementById("publish-images").files || []).slice(0, 3);
    if (!files.length) { notify("请至少选择一张图片", "error"); return; }
    try {
      const uploaded = [];
      for (const file of files) {
        const uploadData = new FormData();
        uploadData.append("file", file);
        uploaded.push(await api("/upload/blog", { method: "POST", body: uploadData }));
      }
      const payload = { title: data.get("title"), content: data.get("content"), images: uploaded.join(",") };
      if (data.get("shopId")) payload.shopId = Number(data.get("shopId"));
      await api("/blog", { method: "POST", body: jsonBody(payload) });
      notify("笔记已发布", "success");
      go("/profile");
    } catch (error) { notify(error.message, "error"); }
  }
  async function performAction(actionElement) {
    const action = actionElement.dataset.action;
    if (action === "ai-open") { state.aiOpen = true; render(); return; }
    if (action === "ai-close") { state.aiOpen = false; render(); return; }
    if (action === "login") { go("/login"); return; }
    if (action === "profile") { go(isLoggedIn() ? "/profile" : "/login"); return; }
    if (action === "publish") { go(isLoggedIn() ? "/publish" : "/login"); return; }
    if (action === "back") { window.history.back(); return; }
    if (action === "reload") { loadRoute(); return; }
    if (action === "type") { goWithQuery("/nearby", { typeId: actionElement.dataset.typeId }); return; }
    if (action === "shop") { if (actionElement.dataset.id) goWithQuery("/shop", { id: actionElement.dataset.id }); return; }
    if (action === "note") { if (actionElement.dataset.id && !String(actionElement.dataset.id).startsWith("demo")) goWithQuery("/note", { id: actionElement.dataset.id }); else notify("这是一条演示笔记，服务连接后可打开详情", ""); return; }
    if (action === "like") {
      if (!isLoggedIn()) { go("/login"); return; }
      try { await api("/blog/like/" + encodeURIComponent(actionElement.dataset.id), { method: "PUT" }); notify("已更新点赞", "success"); } catch (error) { notify(error.message, "error"); }
      return;
    }
    if (action === "seckill") {
      if (!isLoggedIn()) { go("/login"); return; }
      try { const orderId = await api("/voucher-order/seckill/" + encodeURIComponent(actionElement.dataset.id), { method: "POST" }); notify("抢购成功，订单号 " + orderId, "success"); } catch (error) { notify(error.message, "error"); }
      return;
    }
    if (action === "ai-recommend") {
      if (!state.route.query.id) return;
      try { const recommendation = await api("/shop/" + encodeURIComponent(state.route.query.id) + "/ai-recommend"); state.aiRecommendation = typeof recommendation === "string" ? recommendation : String(recommendation || ""); render(); } catch (error) { notify(error.message, "error"); }
      return;
    }
    if (action === "follow") {
      if (!isLoggedIn()) { go("/login"); return; }
      try { await api("/follow/" + encodeURIComponent(actionElement.dataset.id) + "/true", { method: "PUT" }); notify("已关注作者", "success"); } catch (error) { notify(error.message, "error"); }
      return;
    }
    if (action === "send-code") {
      const form = actionElement.closest("form");
      const phone = form && form.querySelector("[name=phone]").value;
      if (!phone) { notify("请先输入手机号", "error"); return; }
      state.loginPhone = phone;
      try { await api("/user/code?phone=" + encodeURIComponent(phone), { method: "POST" }); notify("验证码已发送", "success"); } catch (error) { notify(error.message, "error"); }
      return;
    }
    if (action === "logout") {
      try { if (isLoggedIn()) await api("/user/logout", { method: "POST" }); } catch (error) { /* token cleanup still wins */ }
      sessionStorage.removeItem("token");
      state.user = null;
      notify("已退出登录", "success");
      go("/");
      return;
    }
    if (action === "sign") {
      if (!isLoggedIn()) { go("/login"); return; }
      try { await api("/user/sign", { method: "POST" }); notify("今日签到成功", "success"); loadProfile(); } catch (error) { notify(error.message, "error"); }
    }
  }

  document.addEventListener("click", function (event) {
    const target = event.target.closest("[data-action]");
    if (target) { event.preventDefault(); performAction(target); }
  });
  document.addEventListener("submit", function (event) {
    const form = event.target.closest("form[data-form]");
    if (!form) return;
    event.preventDefault();
    const kind = form.dataset.form;
    if (kind === "search" || kind === "nearby-search") submitSearch(form);
    if (kind === "login") submitLogin(form);
    if (kind === "ai") submitAi(form);
    if (kind === "publish") submitPublish(form);
  });
  window.addEventListener("hashchange", loadRoute);
  window.addEventListener("popstate", loadRoute);

  Vue.createApp({
    setup: function () {
      return { markup: renderedMarkup };
    },
    template: "<div v-html=\"markup\"></div>"
  }).mount("#app");

  state.route = parseHash();
  render();
  loadRoute();
})();
