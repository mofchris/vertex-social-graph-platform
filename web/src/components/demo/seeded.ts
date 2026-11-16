// Mirrors scripts/seed-demo.sh: the seeded people, their sign-in emails and shared password.
// The password is public on purpose: these are fictional demo accounts, reset every night.

const FIRST = ["Avery", "Bailey", "Casey", "Devon", "Ellis", "Finley", "Gray", "Harper", "Indie", "Jordan", "Kai", "Lane", "Max", "Noor",
  "Quinn", "Riley", "Sage", "Tatum", "River", "Sky", "Reese", "Rowan", "Emerson", "Hayden", "Parker", "Drew", "Marlowe", "Phoenix"];
const LAST = ["Okafor", "Lindqvist", "Moreau", "Tanaka", "Haddad", "Kowalski", "Ferreira", "Nakamura", "Osei", "Brennan",
  "Varga", "Castillo", "Achebe", "Holm", "Rinaldi", "Sato", "Mensah", "Novak", "Duarte", "Iwu"];

export const DEMO_PASSWORD = "vertex-demo";
const COUNT = 80;
const CELEBS = 3;

export const seededName = (i: number) => `${FIRST[i % FIRST.length]} ${LAST[i % LAST.length]}`;
export const seededEmail = (i: number) =>
  `${FIRST[i % FIRST.length].toLowerCase()}.${LAST[i % LAST.length].toLowerCase()}@vertex.demo`;

/** Seeded people whose profile is public and who are not one of the three celebrities. */
export function pickPartnerIndex(): number {
  const pool: number[] = [];
  for (let i = CELEBS; i < COUNT; i++) if (i % 5 !== 0 && i % 11 !== 0) pool.push(i);
  return pool[Math.floor(Math.random() * pool.length)];
}

export function randomHandle(): string {
  const bytes = new Uint8Array(4);
  crypto.getRandomValues(bytes);
  return Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
}

export function randomPassword(): string {
  const bytes = new Uint8Array(18);
  crypto.getRandomValues(bytes);
  return btoa(String.fromCharCode(...bytes)).replace(/[^A-Za-z0-9]/g, "").slice(0, 20) + "9a";
}
