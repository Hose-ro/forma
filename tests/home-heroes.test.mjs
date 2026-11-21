import assert from 'node:assert/strict';
import test from 'node:test';
import { HERO_VARIANTS, heroForTime, momentForTime, nextSlotHour, parseHeroVariant } from '../src/lib/home-heroes.ts';

const TZ = 'America/Mexico_City'; // UTC-6 all year (no DST since 2022)
const at = iso => new Date(iso);

test('the hero stays the same within a 12-hour store-time slot', () => {
  const start = heroForTime(at('2026-11-20T06:00:00Z'), TZ); // 00:00 store time
  assert.equal(heroForTime(at('2026-11-20T11:30:00Z'), TZ), start);
  assert.equal(heroForTime(at('2026-11-20T17:59:59Z'), TZ), start); // 11:59:59 store time
});

test('the hero changes at 00:00 and 12:00 store time and cycles through every variant', () => {
  const seen = [];
  for (let slot = 0; slot < HERO_VARIANTS.length * 2; slot++) {
    seen.push(heroForTime(new Date(Date.parse('2026-11-20T06:00:00Z') + slot * 12 * 3_600_000), TZ));
  }
  for (let i = 1; i < seen.length; i++) assert.notEqual(seen[i], seen[i - 1]);
  assert.deepEqual(seen.slice(0, 4).toSorted(), [...HERO_VARIANTS].toSorted());
  assert.deepEqual(seen.slice(4), seen.slice(0, 4));
});

test('slots follow store time, not UTC', () => {
  // 03:00 UTC is still the previous evening (21:00) in Mexico City.
  assert.equal(heroForTime(at('2026-11-21T03:00:00Z'), TZ), heroForTime(at('2026-11-20T20:00:00Z'), TZ));
});

test('the hero D moment follows the store clock', () => {
  assert.equal(momentForTime(at('2026-11-20T14:00:00Z'), TZ), 'manana'); // 08:00
  assert.equal(momentForTime(at('2026-11-20T20:00:00Z'), TZ), 'tarde');  // 14:00
  assert.equal(momentForTime(at('2026-11-21T03:00:00Z'), TZ), 'noche');  // 21:00
  assert.equal(momentForTime(at('2026-11-20T08:00:00Z'), TZ), 'noche');  // 02:00
});

test('only known variants can be previewed with ?hero=', () => {
  assert.equal(parseHeroVariant('C'), 'c');
  assert.equal(parseHeroVariant(['b', 'a']), 'b');
  for (const value of [undefined, '', 'e', 'a; drop', ['x'], 3]) assert.equal(parseHeroVariant(value), null);
});

test("disabled heroes drop out of the rotation", () => {
  const enabled = ["a", "c"];
  const seen = [];
  for (let slot = 0; slot < 6; slot++) {
    seen.push(heroForTime(new Date(Date.parse("2026-11-20T06:00:00Z") + slot * 12 * 3_600_000), TZ, enabled));
  }
  assert.ok(seen.every(v => enabled.includes(v)));
  for (let i = 1; i < seen.length; i++) assert.notEqual(seen[i], seen[i - 1]);
  assert.equal(heroForTime(at("2026-11-20T12:00:00Z"), TZ, ["d"]), "d");
  assert.equal(heroForTime(at("2026-11-20T12:00:00Z"), TZ, []), "a");
});

test("the next change is announced at 12:00 or 00:00 store time", () => {
  assert.equal(nextSlotHour(at("2026-11-20T14:00:00Z"), TZ), 12); // 08:00
  assert.equal(nextSlotHour(at("2026-11-20T20:00:00Z"), TZ), 0);  // 14:00
});
