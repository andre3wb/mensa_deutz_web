// Port of MensaApiClient.java + MensaModels.java (Android) to plain JavaScript.
/* exported MensaApi */
'use strict';

const MensaApi = (() => {
  const DEFAULT_LOCATION = 'Mensa Deutz';
  const AVAILABLE_LOCATIONS = [
    'Mensa Deutz',
    'Mensa Südstadt',
    'Mensa Claudiusstraße',
    'Mensa Zülpicher Straße',
    'Mensa Lindenthal',
    'Mensa Gummersbach',
    'Mensa Leverkusen',
    'Mensa Kunsthochschule',
    'Mensa Musikhochschule',
    'Mensa Am Sportpark Müngersdorf'
  ];

  const SUPABASE_BASE = 'https://axxiebkvmfjmiaanviob.supabase.co/rest/v1/rpc/';
  const RPC_WEEK_MENU_CACHED = SUPABASE_BASE + 'public_get_week_menu_cached';
  const RPC_WEEK_MENU = SUPABASE_BASE + 'public_get_week_menu';
  const API_KEY = 'sb_publishable_G6p4Gfhzcx2AM5ToCWAChA_pY-PKF2a';
  const KSTW_ORG_ID = '4c89c35f-16ac-413f-af04-ec9ffe610f67';
  const CACHE_KEY = 'kstw_week_menu_cache_v1';

  const PAREN_ALLERGEN_PATTERN = /\s*\([0-9a-zA-Z,\s]+\)/g;
  const INTERNAL_WORDS_PATTERN = /\b(TK|Grundrezept|Tagesrestproduktion)\b/gi;

  const PriceTier = {
    STUDENT: { key: 'STUDENT', label: 'Studierende', shortLabel: 'Stud.' },
    EMPLOYEE: { key: 'EMPLOYEE', label: 'Bedienstete', shortLabel: 'Bed.' },
    GUEST: { key: 'GUEST', label: 'Gäste', shortLabel: 'Gäste' }
  };
  const PRICE_TIERS = [PriceTier.STUDENT, PriceTier.EMPLOYEE, PriceTier.GUEST];

  const DietFilter = {
    ALL: { key: 'ALL', label: 'Alle', prefix: '' },
    VEGAN: { key: 'VEGAN', label: 'Vegan', prefix: '🌱 ' },
    VEGETARIAN: { key: 'VEGETARIAN', label: 'Vegetarisch', prefix: '🧀 ' },
    MEAT_FISH: { key: 'MEAT_FISH', label: 'Fleisch & Fisch', prefix: '🍗 ' }
  };
  const DIET_FILTERS = [DietFilter.ALL, DietFilter.VEGAN, DietFilter.VEGETARIAN, DietFilter.MEAT_FISH];

  // ---------- Date helpers (ISO yyyy-mm-dd, Europe/Berlin) ----------

  function berlinTodayIso(date) {
    // en-CA formats as YYYY-MM-DD
    return new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Europe/Berlin', year: 'numeric', month: '2-digit', day: '2-digit'
    }).format(date || new Date());
  }

  function isoToUtcDate(iso) {
    const [y, m, d] = iso.split('-').map(Number);
    return new Date(Date.UTC(y, m - 1, d));
  }

  function utcDateToIso(dt) {
    return dt.toISOString().slice(0, 10);
  }

  function addDays(iso, n) {
    const dt = isoToUtcDate(iso);
    dt.setUTCDate(dt.getUTCDate() + n);
    return utcDateToIso(dt);
  }

  function dayOfWeek(iso) {
    // 0 = Sunday ... 6 = Saturday
    return isoToUtcDate(iso).getUTCDay();
  }

  function epochDay(iso) {
    return Math.round(isoToUtcDate(iso).getTime() / 86400000);
  }

  // ---------- Dish model ----------

  class Dish {
    constructor(o) {
      Object.assign(this, o);
    }

    isVegan() {
      return this.foodIcons.includes('VGN') || this.titleDe.toLowerCase().includes('vegan');
    }

    isVegetarian() {
      return this.isVegan() || this.foodIcons.includes('VGT') || this.menuType.toLowerCase().includes('vegetarisch');
    }

    isMeatOrFish() {
      if (this.foodIcons.some((i) => ['G', 'R', 'S', 'F', 'L', 'W'].includes(i))) return true;
      const mt = this.menuType.toLowerCase();
      return mt.includes('fleisch') || mt.includes('fisch');
    }

    matchesFilter(filter) {
      if (!filter || filter === DietFilter.ALL) return true;
      if (filter === DietFilter.VEGAN) return this.isVegan();
      if (filter === DietFilter.VEGETARIAN) return this.isVegetarian();
      if (filter === DietFilter.MEAT_FISH) return this.isMeatOrFish();
      return true;
    }

    getPrice(tier) {
      if (tier === PriceTier.EMPLOYEE && this.priceEmployee > 0) return this.priceEmployee;
      if (tier === PriceTier.GUEST && this.priceGuest > 0) return this.priceGuest;
      return this.priceStudent;
    }

    getFormattedPrice(tier) {
      const p = this.getPrice(tier);
      if (p <= 0) return '–';
      const s = formatEuro(p);
      return this.isSaladBuffet ? s + ' / 100g' : s;
    }

    getCategoryDisplayLabel() {
      if (this.isSaladBuffet) return 'Salatbuffet';
      if (this.isSoup) return 'Suppe / Eintopf';
      if (this.isDessert) return 'Dessert';
      if (this.isSideDish) return 'Beilage';
      const mt = this.menuType.trim();
      const ml = mt.toLowerCase();
      if (ml === 'vegan/vegetarisch') return this.isVegan() ? 'Vegan' : 'Vegetarisch';
      if (ml === 'fleisch') {
        const fi = this.foodIcons;
        if (fi.includes('G')) return 'Geflügel';
        if (fi.includes('R') && fi.includes('S')) return 'Rind & Schwein';
        if (fi.includes('R')) return 'Rind';
        if (fi.includes('S')) return 'Schwein';
        return 'Fleisch';
      }
      if (ml === 'fisch') return 'Fisch';
      if (ml.includes('querbeet')) return 'Querbeet';
      if (ml.includes('aktion')) return 'Aktion';
      if (ml.includes('streetfood')) return 'Streetfood';
      if (ml.includes('heimspiel')) return 'Heimspiel';
      if (ml.includes('worldwide')) return 'Worldwide';
      if (ml.includes('meisterwerk')) return 'Meisterwerk';
      return mt || 'Hauptgericht';
    }
  }

  function formatEuro(v) {
    return v.toFixed(2).replace('.', ',') + ' €';
  }

  // ---------- Networking ----------

  async function postMenuRpc(rpcUrl, startIso, endIso) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 15000);
    try {
      const res = await fetch(rpcUrl, {
        method: 'POST',
        headers: {
          apikey: API_KEY,
          Authorization: 'Bearer ' + API_KEY,
          'Content-Type': 'application/json; charset=utf-8',
          Accept: 'application/json'
        },
        body: JSON.stringify({
          p_organization_id: KSTW_ORG_ID,
          p_start_date: startIso,
          p_end_date: endIso,
          p_dedup_fields: ['name_de', 'location', 'ort_id', '']
        }),
        signal: controller.signal,
        cache: 'no-store'
      });
      const body = await res.text();
      if (!res.ok) throw new Error('HTTP ' + res.status + ': ' + body.slice(0, 200));

      let serverIso = null;
      const dateHeader = res.headers.get('Date'); // only readable if exposed via CORS
      if (dateHeader) {
        const d = new Date(dateHeader);
        if (!isNaN(d.getTime())) serverIso = berlinTodayIso(d);
      }
      return { rawBody: body, serverIso };
    } finally {
      clearTimeout(timer);
    }
  }

  async function fetchWeekMenu(locationName) {
    const loc = (locationName || '').trim() || DEFAULT_LOCATION;
    let startIso = berlinTodayIso();
    let endIso = addDays(startIso, 13);

    let result;
    try {
      result = await postMenuRpc(RPC_WEEK_MENU_CACHED, startIso, endIso);
    } catch (e) {
      result = await postMenuRpc(RPC_WEEK_MENU, startIso, endIso);
    }

    if (result.serverIso && Math.abs(epochDay(result.serverIso) - epochDay(startIso)) > 1) {
      startIso = result.serverIso;
      endIso = addDays(startIso, 13);
      result = await postMenuRpc(RPC_WEEK_MENU_CACHED, startIso, endIso);
    }

    const effectiveToday = result.serverIso || startIso;
    const now = Date.now();
    saveCache(effectiveToday, now, result.rawBody);
    return parseWeekMenuJson(result.rawBody, effectiveToday, endIso, loc, now, false);
  }

  function loadCachedMenu(locationName) {
    const loc = (locationName || '').trim() || DEFAULT_LOCATION;
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (!raw) return null;
      const wrapper = JSON.parse(raw);
      const startIso = wrapper.serverToday || berlinTodayIso();
      return parseWeekMenuJson(wrapper.rawArray, startIso, addDays(startIso, 13), loc, wrapper.fetchedAt || 0, true);
    } catch (e) {
      return null;
    }
  }

  function saveCache(serverToday, fetchedAt, rawArray) {
    try {
      localStorage.setItem(CACHE_KEY, JSON.stringify({ serverToday, fetchedAt, rawArray }));
    } catch (e) { /* quota exceeded – ignore */ }
  }

  // ---------- Parsing ----------

  function parseWeekMenuJson(rawJsonArray, startIso, endIso, targetLocation, fetchedAt, fromCache) {
    const array = JSON.parse(rawJsonArray);
    const dayMap = new Map();

    for (const dayObj of array || []) {
      const dateIso = dayObj && dayObj.date ? String(dayObj.date).slice(0, 10) : '';
      if (!dateIso) continue;
      if (dayOfWeek(dateIso) === 0) continue; // skip Sunday test entries

      const mainDishes = [];
      const sideDishes = [];
      const desserts = [];
      for (const dishObj of dayObj.dishes || []) {
        if (!dishObj) continue;
        const dish = parseSingleDish(dishObj, targetLocation);
        if (!dish) continue;
        if (dish.isSideDish) {
          (dish.isDessert ? desserts : sideDishes).push(dish);
        } else {
          mainDishes.push(dish);
        }
      }

      mainDishes.sort((a, b) => {
        const ra = a.isSaladBuffet ? 2 : (a.isSoup ? 1 : 0);
        const rb = b.isSaladBuffet ? 2 : (b.isSoup ? 1 : 0);
        if (ra !== rb) return ra - rb;
        return b.priceStudent - a.priceStudent;
      });
      const byTitle = (a, b) => a.titleDe.localeCompare(b.titleDe, 'de', { sensitivity: 'base' });
      sideDishes.sort(byTitle);
      desserts.sort(byTitle);

      dayMap.set(dateIso, makeDay(dateIso, mainDishes, sideDishes, desserts));
    }

    const days = [];
    let cursor = startIso;
    while (epochDay(cursor) <= epochDay(endIso)) {
      const existing = dayMap.get(cursor);
      const dow = dayOfWeek(cursor);
      const isWeekday = dow !== 0 && dow !== 6;
      if (existing && existing.total > 0) {
        days.push(existing);
      } else if (isWeekday || cursor === startIso) {
        days.push(makeDay(cursor, [], [], []));
      }
      cursor = addDays(cursor, 1);
    }

    return { serverTodayIso: startIso, locationName: targetLocation, fetchedAt, fromCache, days };
  }

  function makeDay(dateIso, mainDishes, sideDishes, desserts) {
    return { dateIso, mainDishes, sideDishes, desserts, total: mainDishes.length + sideDishes.length + desserts.length };
  }

  function parseSingleDish(dishObj, targetLocation) {
    const cf = {};
    for (const item of dishObj.custom_fields || []) {
      if (!item) continue;
      const fid = item.field_id || '';
      const val = item.value;
      if (fid && val !== null && val !== undefined && String(val) !== 'null') {
        cf[fid] = String(val).trim();
      }
    }
    const get = (k) => cf[k] || '';
    const target = targetLocation.toLowerCase();

    let matchesLocation = get('location').toLowerCase() === target;
    let counterLocation = null;
    let anyActiveScreen = false;
    let allActiveSoldOut = true;

    for (const sc of dishObj.screens || []) {
      if (!sc) continue;
      if (String(sc.screen_group_name || '').toLowerCase() !== target) continue;
      matchesLocation = true;
      const locLabel = String(sc.location || '');
      const idx = locLabel.indexOf('Theke');
      if (idx >= 0) counterLocation = locLabel.substring(idx).trim();
      if (!sc.is_powered_off) {
        anyActiveScreen = true;
        if (!sc.is_sold_out) allActiveSoldOut = false;
      }
    }
    if (!matchesLocation) return null;

    const menuType = get('menu_type');
    const menuTypeLower = menuType.toLowerCase();
    if (!menuType || menuTypeLower.includes('xbeilagen') || menuTypeLower.includes('xsonstiges')) return null;

    const partsDe = [];
    const partsEn = [];
    for (let i = 1; i <= 5; i++) {
      const pDe = cleanDishText(get('dish_ger_' + i));
      if (pDe) partsDe.push(pDe);
      const pEn = cleanDishText(get('dish_' + i + '_eng'));
      if (pEn) partsEn.push(pEn);
    }

    let titleDe = cleanDishText(String(dishObj.name_de || '').trim());
    if (!titleDe && partsDe.length) titleDe = partsDe[0];
    let titleEn = cleanDishText(String(dishObj.name_en || '').trim());
    if (!titleEn && partsEn.length) titleEn = partsEn[0];

    const subtitleDe = partsDe.filter((p, i) => !(i === 0 && isSameText(p, titleDe))).join(' · ');
    const subtitleEn = partsEn.filter((p, i) => !(i === 0 && isSameText(p, titleEn))).join(' · ');

    const hinweise = get('hinweise');
    const lowerTitle = titleDe.toLowerCase();
    const isSideDish = menuTypeLower.includes('beilage');
    const isSaladBuffet = lowerTitle.includes('salatbuffet');
    const isSoup = hinweise.toLowerCase().includes('suppenpool')
      || lowerTitle.includes('suppe') || lowerTitle.includes('eintopf') || lowerTitle.includes('rahmtopf');
    const dessertWords = ['pudding', 'quark', 'joghurt', 'mousse', 'dessert', 'obst', 'kompott', 'creme',
      'tiramisu', 'milchreis', 'grieß', 'apfelmus', 'kuchen', 'brownie'];
    const isDessert = isSideDish && dessertWords.some((w) => lowerTitle.includes(w));

    const priceStudent = Number(dishObj.price) || 0;
    const priceEmployee = parseGermanPrice(get('price_2'));
    const priceGuest = parseGermanPrice(get('price_3'));

    let imageUrl = dishObj.image_url ? String(dishObj.image_url).trim() : null;
    if (!imageUrl) imageUrl = null;

    const foodIcons = [];
    for (const token of get('food_icon').split(',')) {
      const t = token.trim().toUpperCase();
      if (t && !foodIcons.includes(t)) foodIcons.push(t);
    }

    const allergensList = parseAllergens(get('allergens_names'));
    const isAvailable = dishObj.is_available === undefined || dishObj.is_available === null ? true : !!dishObj.is_available;
    const isSoldOut = (anyActiveScreen && allActiveSoldOut) || !isAvailable;

    return new Dish({
      id: String(dishObj.id || titleDe),
      titleDe, titleEn, subtitleDe, subtitleEn,
      componentsDe: partsDe,
      menuType, poolHint: hinweise,
      isSideDish, isSaladBuffet, isSoup, isDessert,
      priceStudent, priceEmployee, priceGuest,
      imageUrl, foodIcons, allergensList, counterLocation, isSoldOut
    });
  }

  function cleanDishText(input) {
    if (!input) return '';
    let s = input.replace(PAREN_ALLERGEN_PATTERN, '');
    s = s.replace(INTERNAL_WORDS_PATTERN, '');
    s = s.replace(/\s{2,}/g, ' ').trim();
    s = s.replace(/^[,\s]+|[,\s]+$/g, '');
    return s;
  }

  function isSameText(a, b) {
    if (a == null || b == null) return false;
    const n = (x) => x.toLowerCase().replace(/[^a-zäöüß0-9]/g, '');
    return n(a) === n(b);
  }

  function parseGermanPrice(raw) {
    if (!raw || !raw.trim()) return 0;
    const v = parseFloat(raw.trim().replace('€', '').replace(',', '.').trim());
    return isFinite(v) ? v : 0;
  }

  function parseAllergens(raw) {
    if (!raw || !raw.trim()) return [];
    const result = new Set();
    for (const part of raw.split(',')) {
      const trimmed = part.trim();
      if (!trimmed) continue;
      // Format e.g. "18=Enthält Laktose | contains lactose"
      const eq = trimmed.indexOf('=');
      const code = eq > 0 ? trimmed.substring(0, eq).trim() : '';
      const rest = eq > 0 ? trimmed.substring(eq + 1).trim() : trimmed;
      const pipe = rest.indexOf('|');
      const german = pipe > 0 ? rest.substring(0, pipe).trim() : rest;
      if (german) result.add(code ? german + ' (' + code + ')' : german);
    }
    return [...result];
  }

  return {
    DEFAULT_LOCATION, AVAILABLE_LOCATIONS,
    PriceTier, PRICE_TIERS, DietFilter, DIET_FILTERS,
    fetchWeekMenu, loadCachedMenu, parseWeekMenuJson, cleanDishText, parseGermanPrice, parseAllergens,
    berlinTodayIso, addDays, dayOfWeek, isoToUtcDate, formatEuro
  };
})();
