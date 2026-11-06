// UI layer – port of MainActivity.java (Android) to the web.
/* global MensaApi */
'use strict';

(() => {
  const { PriceTier, PRICE_TIERS, DietFilter, DIET_FILTERS } = MensaApi;

  const PREFS = {
    dark: 'mensa_dark_mode',
    tier: 'mensa_price_tier',
    location: 'mensa_location_name',
    hintDismissed: 'mensa_install_hint_dismissed'
  };

  // ---------- State ----------
  const sysDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
  const storedDark = localStorage.getItem(PREFS.dark);
  const state = {
    isDark: storedDark === null ? sysDark : storedDark === 'true',
    tier: PriceTier[localStorage.getItem(PREFS.tier)] || PriceTier.STUDENT,
    filter: DietFilter.ALL,
    location: localStorage.getItem(PREFS.location) || MensaApi.DEFAULT_LOCATION,
    response: null,
    dayIndex: 0,
    loading: false,
    tray: new Map() // dish id -> Dish
  };

  // ---------- DOM refs ----------
  const $ = (id) => document.getElementById(id);
  const el = {
    headerTitle: $('headerTitle'),
    headerStatus: $('headerStatus'),
    locationBtn: $('locationBtn'),
    tierBtn: $('tierBtn'),
    themeBtn: $('themeBtn'),
    refreshBtn: $('refreshBtn'),
    dayStrip: $('dayStrip'),
    filterStrip: $('filterStrip'),
    content: $('content'),
    trayBar: $('trayBar'),
    trayTitle: $('trayTitle'),
    traySubtitle: $('traySubtitle'),
    trayClear: $('trayClear'),
    modalRoot: $('modalRoot'),
    toast: $('toast')
  };

  /** Small DOM builder: h('div.cls1.cls2', {attrs/props}, ...children). Strings become text nodes (XSS-safe). */
  function h(tag, props, ...children) {
    const [name, ...classes] = tag.split('.');
    const node = document.createElement(name || 'div');
    if (classes.length) node.className = classes.join(' ');
    if (props) {
      for (const [k, v] of Object.entries(props)) {
        if (v === undefined || v === null || v === false) continue;
        if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
        else if (k.startsWith('on')) node.addEventListener(k.slice(2), v);
        else if (k === 'class') node.className += ' ' + v;
        else if (k in node) node[k] = v;
        else node.setAttribute(k, v);
      }
    }
    for (const c of children.flat()) {
      if (c === null || c === undefined || c === false) continue;
      node.append(c instanceof Node ? c : document.createTextNode(String(c)));
    }
    return node;
  }

  // ---------- Theme / colors (ThemePalette.java) ----------

  function badgeColors(code) {
    const d = state.isDark;
    switch (code) {
      case 'VGN': return d ? ['#064E3B', '#6EE7B7'] : ['#DCFCE7', '#15803D'];
      case 'VGT': return d ? ['#065F46', '#A7F3D0'] : ['#D1FAE5', '#047857'];
      case 'G': return d ? ['#451A03', '#FCD34D'] : ['#FEF3C7', '#B45309'];
      case 'R': case 'S': case 'L': case 'W': return d ? ['#450A0A', '#FCA5A5'] : ['#FEE2E2', '#B91C1C'];
      case 'F': return d ? ['#082F49', '#7DD3FC'] : ['#E0F2FE', '#0369A1'];
      case 'NL': return d ? ['#422006', '#FDE047'] : ['#FEF9C3', '#854D0E'];
      default: return d ? ['#1E293B', '#CBD5E1'] : ['#F1F5F9', '#475569'];
    }
  }

  const BADGE_LABELS = {
    VGN: '🌱 Vegan', VGT: '🧀 Vegetarisch', G: '🍗 Geflügel', R: '🥩 Rind', S: '🥓 Schwein',
    F: '🐟 Fisch', L: '🍖 Lamm', W: '🦌 Wild', A: '🍷 Alkohol', NL: '🌾 Neuland', RK: '🥔 Rettet die Knolle'
  };

  function categoryColor(dish) {
    const d = state.isDark;
    if (dish.isSaladBuffet) return d ? '#34D399' : '#059669';
    if (dish.isSoup) return d ? '#FBBF24' : '#D97706';
    if (dish.isDessert) return d ? '#F472B6' : '#DB2777';
    if (dish.isSideDish) return d ? '#94A3B8' : '#64748B';
    if (dish.isVegan()) return d ? '#34D399' : '#16A34A';
    if (dish.isVegetarian()) return d ? '#2DD4BF' : '#0D9488';
    const mt = dish.menuType.toLowerCase();
    if (mt.includes('fisch') || dish.foodIcons.includes('F')) return d ? '#38BDF8' : '#0284C7';
    if (mt.includes('aktion')) return d ? '#FBBF24' : '#D97706';
    return d ? '#F87171' : '#DC2626';
  }

  function applyTheme() {
    document.documentElement.dataset.theme = state.isDark ? 'dark' : 'light';
    document.querySelectorAll('meta[name="theme-color"]').forEach((m) => {
      m.setAttribute('content', state.isDark ? '#111827' : '#FFFFFF');
    });
    el.themeBtn.textContent = state.isDark ? '☀️' : '🌙';
    el.tierBtn.textContent = '💶 ' + state.tier.shortLabel;
  }

  // ---------- Formatting ----------

  const WEEKDAYS_SHORT = ['SO', 'MO', 'DI', 'MI', 'DO', 'FR', 'SA'];

  function shortDate(iso) {
    const [, m, d] = iso.split('-');
    return d + '.' + m + '.';
  }

  function fullDate(iso) {
    const s = new Intl.DateTimeFormat('de-DE', {
      weekday: 'long', day: '2-digit', month: 'long', year: 'numeric', timeZone: 'UTC'
    }).format(MensaApi.isoToUtcDate(iso));
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  function timeHHMM(ms) {
    return new Intl.DateTimeFormat('de-DE', { hour: '2-digit', minute: '2-digit', timeZone: 'Europe/Berlin' })
      .format(new Date(ms));
  }

  function berlinNowParts() {
    const parts = new Intl.DateTimeFormat('en-GB', {
      timeZone: 'Europe/Berlin', weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false
    }).formatToParts(new Date());
    const get = (t) => (parts.find((p) => p.type === t) || {}).value;
    return { weekday: get('weekday'), minutes: Number(get('hour')) * 60 + Number(get('minute')) };
  }

  function sideDishEmoji(dish) {
    if (dish.isDessert) return '🍮';
    const t = dish.titleDe.toLowerCase();
    const any = (...w) => w.some((x) => t.includes(x));
    if (any('pommes', 'wedges')) return '🍟';
    if (any('kartoffel', 'drillinge', 'kroketten', 'püree')) return '🥔';
    if (any('reis')) return '🍚';
    if (any('salat')) return '🥗';
    if (any('gemüse', 'brokkoli', 'blumenkohl', 'rosenkohl', 'erbsen', 'karotten', 'bohnen', 'rotkohl')) return '🥦';
    if (any('nudel', 'pasta', 'spätzle')) return '🍝';
    return '🥣';
  }

  // ---------- Toast ----------
  let toastTimer = null;
  function toast(msg) {
    el.toast.textContent = msg;
    el.toast.classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.toast.classList.add('hidden'), 2200);
  }

  // ---------- Header ----------

  function updateHeader() {
    el.headerTitle.textContent = (state.location === 'Mensa Deutz' ? 'Mensa IWZ Deutz' : state.location) + ' ▾';
    const { weekday, minutes } = berlinNowParts();
    const isWeekday = weekday !== 'Sat' && weekday !== 'Sun';
    const open = isWeekday && minutes >= 11 * 60 + 30 && minutes <= 14 * 60 + 45;
    const openStatus = open ? '🟢 Geöffnet (bis 14:45)' : '🕒 Mo–Fr 11:30–14:45 Uhr';
    if (state.loading) el.headerStatus.textContent = '⏳ Aktualisiere Speiseplan...';
    else if (state.response) el.headerStatus.textContent = openStatus + ' · Stand ' + timeHHMM(state.response.fetchedAt);
    else el.headerStatus.textContent = openStatus;
  }

  // ---------- Data loading ----------

  let refreshRotation = 0;

  async function triggerRefresh(showFullLoader) {
    if (state.loading) return;
    state.loading = true;
    updateHeader();
    refreshRotation += 360;
    el.refreshBtn.style.transform = 'rotate(' + refreshRotation + 'deg)';
    if (showFullLoader && !state.response) renderLoading();

    const loc = state.location;
    try {
      const fresh = await MensaApi.fetchWeekMenu(loc);
      if (loc !== state.location) return; // user switched location meanwhile
      const prevIso = state.response && state.response.days[state.dayIndex] ? state.response.days[state.dayIndex].dateIso : null;
      state.response = fresh;
      let idx = prevIso ? fresh.days.findIndex((d) => d.dateIso === prevIso) : -1;
      if (idx < 0) idx = bestInitialDayIndex(fresh);
      state.dayIndex = idx;
      state.loading = false;
      updateHeader();
      renderAll();
    } catch (e) {
      state.loading = false;
      updateHeader();
      if (!state.response) renderError(e && e.message);
      else toast('Offline-Modus: Gespeicherter Speiseplan wird angezeigt');
    } finally {
      state.loading = false;
    }
  }

  function bestInitialDayIndex(resp) {
    if (!resp || !resp.days.length) return 0;
    const i = resp.days.findIndex((d) => d.total > 0);
    return i < 0 ? 0 : i;
  }

  function selectDay(index, scrollTop) {
    if (!state.response || index < 0 || index >= state.response.days.length) return;
    state.dayIndex = index;
    renderDayStrip();
    renderFilterStrip();
    renderContent();
    if (scrollTop) window.scrollTo({ top: 0, behavior: 'smooth' });
    scrollDayIntoView();
  }

  function scrollDayIntoView() {
    const tab = el.dayStrip.children[state.dayIndex];
    if (!tab) return;
    const left = tab.offsetLeft - (el.dayStrip.clientWidth - tab.clientWidth) / 2;
    el.dayStrip.scrollTo({ left: Math.max(0, left), behavior: 'smooth' });
  }

  // ---------- Rendering ----------

  function renderAll() {
    renderDayStrip();
    renderFilterStrip();
    renderContent();
    updateTrayBar();
    requestAnimationFrame(scrollDayIntoView);
  }

  function renderDayStrip() {
    el.dayStrip.replaceChildren();
    const resp = state.response;
    if (!resp || !resp.days.length) return;
    const today = resp.serverTodayIso;
    const tomorrow = MensaApi.addDays(today, 1);

    resp.days.forEach((day, i) => {
      const isToday = day.dateIso === today;
      const label = isToday ? 'HEUTE' : day.dateIso === tomorrow ? 'MORGEN' : WEEKDAYS_SHORT[MensaApi.dayOfWeek(day.dateIso)];
      const count = day.total > 0
        ? day.mainDishes.length + ' Haupt · ' + (day.sideDishes.length + day.desserts.length) + ' Beil.'
        : 'Geschlossen';
      el.dayStrip.append(
        h('button.day-tab', { type: 'button', class: i === state.dayIndex ? 'selected' : '', onclick: () => selectDay(i, true) },
          h('span.d1', { class: isToday ? 'today' : '' }, label),
          h('span.d2', null, shortDate(day.dateIso)),
          h('span.d3', null, count))
      );
    });
  }

  function countMatching(day, filter) {
    return [...day.mainDishes, ...day.sideDishes, ...day.desserts].filter((d) => d.matchesFilter(filter)).length;
  }

  function renderFilterStrip() {
    el.filterStrip.replaceChildren();
    const resp = state.response;
    if (!resp || !resp.days.length) return;
    const day = resp.days[state.dayIndex];
    for (const f of DIET_FILTERS) {
      el.filterStrip.append(
        h('button.filter-chip', {
          type: 'button',
          class: f === state.filter ? 'selected' : '',
          onclick: () => { state.filter = f; renderFilterStrip(); renderContent(); }
        }, f.prefix + f.label + ' (' + countMatching(day, f) + ')')
      );
    }
  }

  function renderLoading() {
    el.content.replaceChildren(
      h('div.state-box', null,
        h('div.spinner'),
        h('div.s', { style: { marginTop: '16px' } }, 'Lade aktuellen Speiseplan der ' + state.location + '...'))
    );
  }

  function renderError(msg) {
    el.content.replaceChildren(
      h('div.card.state-box', null,
        h('div.emoji', null, '📡'),
        h('div.t', null, 'Speiseplan konnte nicht geladen werden'),
        h('div.s', null, 'Bitte überprüfe deine Internetverbindung.\n(' + (msg || 'Verbindungsfehler') + ')'),
        h('button.primary-btn', { type: 'button', onclick: () => triggerRefresh(true) }, 'Erneut versuchen'))
    );
    maybeAppendInstallHint();
  }

  function renderContent() {
    const resp = state.response;
    el.content.replaceChildren();
    if (!resp || !resp.days.length) return;
    const day = resp.days[state.dayIndex];
    const hasPrev = state.dayIndex > 0;
    const hasNext = state.dayIndex < resp.days.length - 1;

    el.content.append(
      h('div.card.date-banner', null,
        h('button.arrow', { type: 'button', disabled: !hasPrev, onclick: () => selectDay(state.dayIndex - 1, true) }, '◀'),
        h('div.center', null,
          h('div.full', null, fullDate(day.dateIso)),
          h('div.hint', null, 'Preise für ' + state.tier.label + ' · Tippe auf ein Gericht für Allergene')),
        h('button.arrow', { type: 'button', disabled: !hasNext, onclick: () => selectDay(state.dayIndex + 1, true) }, '▶'))
    );

    if (day.total === 0) {
      el.content.append(
        h('div.card.state-box', null,
          h('div.emoji', null, '☕'),
          h('div.t', null, 'Keine Speisen für diesen Tag gemeldet'),
          h('div.s', null, 'Die Mensa hat Mo–Fr von 11:30 bis 14:45 Uhr geöffnet. Wähle oben einfach den nächsten offenen Tag aus.'))
      );
      maybeAppendInstallHint();
      return;
    }

    const f = state.filter;
    const mains = day.mainDishes.filter((d) => d.matchesFilter(f));
    const sides = day.sideDishes.filter((d) => d.matchesFilter(f));
    const desserts = day.desserts.filter((d) => d.matchesFilter(f));

    el.content.append(sectionHeader('🍛 Hauptspeisen', mains.length + (mains.length === 1 ? ' Gericht' : ' Gerichte'),
      'Wähle dein Hauptgericht an den Ausgabetheken'));
    if (!mains.length) el.content.append(noMatch('Keine Hauptspeisen für den Filter "' + f.label + '" an diesem Tag.'));
    else mains.forEach((d) => el.content.append(mainDishCard(d)));

    el.content.append(sectionHeader('🍟 Mögliche Beilagen', sides.length + (sides.length === 1 ? ' Beilage' : ' Beilagen'),
      'Frei kombinierbare Beilagen & Salate des Tages'));
    if (!sides.length) el.content.append(noMatch('Keine Beilagen für diesen Filter verfügbar.'));
    else el.content.append(sideGrid(sides));

    if (desserts.length) {
      el.content.append(sectionHeader('🍮 Desserts & Süßes', desserts.length + (desserts.length === 1 ? ' Dessert' : ' Desserts'),
        'Aus dem Beilagen- und Dessertpool'));
      el.content.append(sideGrid(desserts));
    }
    maybeAppendInstallHint();
  }

  function sectionHeader(title, count, sub) {
    return h('div.section-header', null,
      h('div.row', null, h('div.title', null, title), h('div.count', null, count)),
      sub ? h('div.sub', null, sub) : null);
  }

  function noMatch(msg) {
    return h('div.card.no-match', null, msg);
  }

  function trayButton(dish, cls, labelOff) {
    const inTray = state.tray.has(dish.id);
    return h(cls, {
      type: 'button',
      class: inTray ? 'active' : '',
      onclick: (e) => { e.stopPropagation(); toggleTray(dish); }
    }, inTray ? '✓ Im Tablett' : labelOff);
  }

  function mainDishCard(dish) {
    const inTray = state.tray.has(dish.id);
    const color = categoryColor(dish);
    return h('div.card.dish-card', { class: inTray ? 'in-tray' : '', onclick: () => showDishDetail(dish) },
      h('div.strip', { style: { background: color } }),
      h('div.body', null,
        h('div.top', null,
          h('span.cat', { style: { color } }, dish.getCategoryDisplayLabel().toUpperCase()),
          dish.counterLocation ? h('span.counter', null, '· 📍 ' + dish.counterLocation) : null,
          dish.isSoldOut ? h('span.soldout', null, 'AUSVERKAUFT') : null,
          h('span.spacer'),
          h('span.price-badge', null, dish.getFormattedPrice(state.tier))),
        h('div.mid', null,
          h('div.texts', null,
            h('div.title', { class: dish.isSoldOut ? 'muted' : '' }, dish.titleDe),
            dish.subtitleDe ? h('div.subtitle', null, 'Dazu: ' + dish.subtitleDe) : null),
          dish.imageUrl ? h('img.thumb', { src: dish.imageUrl, alt: '', loading: 'lazy', onerror: (e) => e.target.remove() }) : null),
        h('div.bottom', null,
          h('div.badges', null, dish.foodIcons.map((code) => {
            const [bg, fg] = badgeColors(code);
            return h('span.food-badge', { style: { background: bg, color: fg } }, BADGE_LABELS[code] || code);
          })),
          trayButton(dish, 'button.tray-btn', '+ Tablett')))
    );
  }

  function sideGrid(dishes) {
    return h('div.side-grid', null, dishes.map(sideCard));
  }

  function sideCard(dish) {
    const inTray = state.tray.has(dish.id);
    const code = dish.isVegan() ? 'VGN' : dish.isVegetarian() ? 'VGT' : '';
    const label = dish.isVegan() ? '🌱 Vegan' : dish.isVegetarian() ? '🧀 Veggie' : '🍽️ Beilage';
    const [bg, fg] = badgeColors(code);
    return h('div.card.side-card', { class: inTray ? 'in-tray' : '', onclick: () => showDishDetail(dish) },
      h('div.top', null,
        h('span.diet', { style: { background: bg, color: fg } }, label),
        h('span.price', null, dish.getFormattedPrice(state.tier))),
      h('div.title', null, sideDishEmoji(dish) + ' ' + dish.titleDe),
      trayButton(dish, 'button.add', '+ Zum Tablett'));
  }

  // ---------- Tray calculator ----------

  function toggleTray(dish) {
    if (state.tray.has(dish.id)) state.tray.delete(dish.id);
    else state.tray.set(dish.id, dish);
    updateTrayBar();
    renderContent();
  }

  function updateTrayBar() {
    if (!state.tray.size) {
      el.trayBar.classList.add('hidden');
      return;
    }
    el.trayBar.classList.remove('hidden');
    const dishes = [...state.tray.values()];
    const sum = dishes.reduce((s, d) => s + d.getPrice(state.tier), 0);
    el.trayTitle.textContent = '🍽️ Dein Tablett (' + dishes.length + ') · ' + MensaApi.formatEuro(sum);
    el.traySubtitle.textContent = dishes.map((d) => d.titleDe).join(' + ');
  }

  function showTraySummary() {
    if (!state.tray.size) return;
    const dishes = [...state.tray.values()];
    const sum = (tier) => dishes.reduce((s, d) => s + d.getPrice(tier), 0);
    openSheet((close) => [
      h('h2', null, '🍽️ Dein Mensa-Tablett'),
      dishes.map((d) => h('div.tray-row', null,
        h('span.n', null, '• ' + d.titleDe),
        h('span.p', null, d.getFormattedPrice(state.tier)))),
      h('div.divider'),
      h('div.tray-total', null,
        h('b', null, 'Gesamt (' + state.tier.label + '): ' + MensaApi.formatEuro(sum(state.tier))),
        h('br'),
        'Studierende: ' + MensaApi.formatEuro(sum(PriceTier.STUDENT)) +
        ' · Bedienstete: ' + MensaApi.formatEuro(sum(PriceTier.EMPLOYEE)) +
        ' · Gäste: ' + MensaApi.formatEuro(sum(PriceTier.GUEST))),
      h('button.primary-btn', { type: 'button', onclick: close }, 'Schließen')
    ]);
  }

  // ---------- Sheets / dialogs ----------

  function openSheet(build) {
    const close = () => {
      backdrop.remove();
      document.body.style.overflow = '';
    };
    const sheet = h('div.sheet', { onclick: (e) => e.stopPropagation() });
    const backdrop = h('div.modal-backdrop', { onclick: close }, sheet);
    sheet.append(...build(close).flat().filter(Boolean));
    el.modalRoot.append(backdrop);
    document.body.style.overflow = 'hidden';
    return close;
  }

  function showDishDetail(dish) {
    let catLine = dish.getCategoryDisplayLabel().toUpperCase();
    if (dish.counterLocation) catLine += ' · 📍 ' + state.location + ' ' + dish.counterLocation;
    let en = '';
    if (dish.titleEn) en = '🇬🇧 ' + dish.titleEn + (dish.subtitleEn ? ' (' + dish.subtitleEn + ')' : '');

    const priceCol = (label, tier) => h('div.col', { class: state.tier === tier ? 'hl' : '' },
      h('div.l', null, label), h('div.v', null, dish.getFormattedPrice(tier)));

    openSheet((close) => [
      dish.imageUrl ? h('img.hero', { src: dish.imageUrl, alt: '', onerror: (e) => e.target.remove() }) : null,
      h('div.cat', { style: { color: categoryColor(dish) } }, catLine),
      h('h2', null, dish.titleDe),
      dish.subtitleDe ? h('div.comp', null, 'Dazu: ' + dish.subtitleDe) : null,
      en ? h('div.en', null, en) : null,
      h('div.price-box', null,
        priceCol('Studierende', PriceTier.STUDENT),
        priceCol('Bedienstete', PriceTier.EMPLOYEE),
        priceCol('Gäste', PriceTier.GUEST)),
      h('h3', null, 'Allergene & Zusatzstoffe'),
      dish.allergensList.length
        ? h('div.allergens', null, '• ' + dish.allergensList.join('\n• '))
        : h('div.allergens.ok', null, '✓ Keine deklarationspflichtigen Allergene oder Zusatzstoffe angegeben.'),
      h('button.primary-btn', { type: 'button', onclick: close }, 'Fertig')
    ]);
  }

  function showLocationPicker() {
    openSheet((close) => [
      h('h2', { style: { marginBottom: '8px' } }, 'Standort wählen'),
      MensaApi.AVAILABLE_LOCATIONS.map((loc) => h('button.loc-item', {
        type: 'button',
        class: loc.toLowerCase() === state.location.toLowerCase() ? 'selected' : '',
        onclick: () => { close(); changeLocation(loc); }
      }, h('span.radio'), loc)),
      h('button.secondary-btn', { type: 'button', onclick: close }, 'Abbrechen')
    ]);
  }

  function changeLocation(loc) {
    if (loc === state.location) return;
    state.location = loc;
    localStorage.setItem(PREFS.location, loc);
    state.tray.clear();
    updateTrayBar();
    updateHeader();
    const cached = MensaApi.loadCachedMenu(loc);
    state.response = cached;
    state.dayIndex = bestInitialDayIndex(cached);
    if (cached) renderAll();
    triggerRefresh(!cached);
  }

  // ---------- iOS "Add to Home Screen" hint ----------

  function isStandalone() {
    return window.navigator.standalone === true || window.matchMedia('(display-mode: standalone)').matches;
  }

  function isIos() {
    return /iphone|ipad|ipod/i.test(navigator.userAgent)
      || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  function maybeAppendInstallHint() {
    if (isStandalone() || !isIos() || localStorage.getItem(PREFS.hintDismissed)) return;
    const box = h('div.card.install-hint', null,
      h('button.close', {
        type: 'button', 'aria-label': 'Schließen',
        onclick: () => { localStorage.setItem(PREFS.hintDismissed, '1'); box.remove(); }
      }, '×'),
      h('b', null, '📲 Als App installieren'), h('br'),
      'Tippe in Safari unten auf ', h('b', null, 'Teilen'), ' (□↑) und dann auf ',
      h('b', null, '„Zum Home-Bildschirm"'), '.');
    el.content.append(box);
  }

  // ---------- Swipe between days ----------

  function setupSwipe() {
    let sx = 0, sy = 0, st = 0, tracking = false;
    el.content.addEventListener('touchstart', (e) => {
      if (e.touches.length !== 1) return;
      tracking = true;
      sx = e.touches[0].clientX;
      sy = e.touches[0].clientY;
      st = Date.now();
    }, { passive: true });
    el.content.addEventListener('touchend', (e) => {
      if (!tracking || !state.response) return;
      tracking = false;
      const t = e.changedTouches[0];
      const dx = t.clientX - sx;
      const dy = t.clientY - sy;
      const dt = Date.now() - st;
      if (Math.abs(dx) > 70 && Math.abs(dx) > Math.abs(dy) * 1.6 && dt < 600) {
        if (dx < 0) selectDay(state.dayIndex + 1, true);
        else selectDay(state.dayIndex - 1, true);
      }
    }, { passive: true });
  }

  // ---------- Wiring ----------

  el.locationBtn.addEventListener('click', showLocationPicker);
  el.themeBtn.addEventListener('click', () => {
    state.isDark = !state.isDark;
    localStorage.setItem(PREFS.dark, String(state.isDark));
    applyTheme();
    renderAll();
  });
  el.tierBtn.addEventListener('click', () => {
    state.tier = PRICE_TIERS[(PRICE_TIERS.indexOf(state.tier) + 1) % PRICE_TIERS.length];
    localStorage.setItem(PREFS.tier, state.tier.key);
    applyTheme();
    renderContent();
    updateTrayBar();
    toast('Preise für: ' + state.tier.label);
  });
  el.refreshBtn.addEventListener('click', () => triggerRefresh(false));
  el.trayBar.addEventListener('click', showTraySummary);
  el.trayClear.addEventListener('click', (e) => {
    e.stopPropagation();
    state.tray.clear();
    updateTrayBar();
    renderContent();
  });

  // Refresh when the app comes back to the foreground (e.g. reopened from home screen).
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState !== 'visible') return;
    updateHeader();
    const age = state.response ? Date.now() - state.response.fetchedAt : Infinity;
    if (age > 10 * 60 * 1000) triggerRefresh(false);
  });
  setInterval(updateHeader, 60 * 1000);

  // ---------- Start ----------

  applyTheme();
  setupSwipe();
  updateHeader();

  const cached = MensaApi.loadCachedMenu(state.location);
  if (cached && cached.days.length) {
    state.response = cached;
    state.dayIndex = bestInitialDayIndex(cached);
    renderAll();
  }
  triggerRefresh(!cached);

  if ('serviceWorker' in navigator) {
    window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
  }
})();
