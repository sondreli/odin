// Sample data for Odin — Norwegian personal finance app

const CATEGORIES = [
  // Income
  { id: 'lonn', name: 'Lønn', color: '#5ce67e', bucket: null, type: 'income' },
  // Expenses
  { id: 'ting', name: 'ting', color: '#6e8eef', bucket: 'wants' },
  { id: 'mat', name: 'mat', color: '#a4e65c', bucket: 'needs' },
  { id: 'mat-ute', name: 'mat ute', color: '#7ce69d', bucket: 'wants' },
  { id: 'bil', name: 'bil', color: '#b56cf0', bucket: 'needs' },
  { id: 'faste', name: 'faste utgifter', color: '#ec6cc4', bucket: 'needs' },
  { id: 'abonoment', name: 'abonoment', color: '#d4e65c', bucket: 'should' },
  { id: 'aktivitet', name: 'aktivitet', color: '#5cb3e6', bucket: 'wants' },
  { id: 'kreditt', name: 'kredittlån', color: '#5ce6c4', bucket: 'should' },
  { id: 'bolig', name: 'bolig', color: '#ef6b5c', bucket: 'needs' },
  { id: 'ferie', name: 'ferie', color: '#f0935c', bucket: 'wants' },
  { id: 'sparing', name: 'sparing', color: '#ec6c9a', bucket: 'should' },
  { id: 'hytte', name: 'hytte', color: '#a464e6', bucket: 'wants' },
  { id: 'ukat-ut', name: 'ukategorisert', color: '#9ca3af', bucket: null },
];

const TAGS = [
  { id: 'lan', name: 'Lån', color: '#3b82f6' },
  { id: 'jobb', name: 'Jobb', color: '#f59e0b' },
  { id: 'barn', name: 'Barn', color: '#ec4899' },
];

// Generate a month of realistic Norwegian transactions for May 2026
function makeTransactions() {
  const txns = [];
  let id = 1;
  const add = (date, desc, amount, catId, tagIds = [], markedByFilter = false) => {
    txns.push({
      id: id++, date, description: desc, amount,
      categoryId: catId, tagIds, markedByFilter,
    });
  };

  // May 2026 — paid 15th
  add('15.5.2026', 'REMA HOLMLIA', -736.80, 'mat');
  add('15.5.2026', 'TV 2 NO O-16534833', -169.00, 'abonoment');
  add('15.5.2026', 'BUNNPRIS HOLMLI NORDÅSVEIEN OSLO', -311.30, 'mat');
  add('15.5.2026', 'OSLO KLATRESENT OLAF HELSETS OSLO', -123.00, 'aktivitet');
  add('15.5.2026', 'COOP PRIX HOLML DYRETRÅKKET OSLO', -361.20, 'mat');
  add('15.5.2026', 'Fair Collection AS', 176.93, 'lonn');
  add('15.5.2026', 'OSLO KLATRESENT OLAF HELSETS OSLO', -150.00, 'aktivitet');
  add('15.5.2026', 'RUTERAPPEN', -655.00, 'faste');
  add('15.5.2026', 'Terminbeløp mai. 2026', -2032.00, 'kreditt', ['lan'], true);
  add('14.5.2026', 'Google YouTube', -49.00, 'abonoment');
  add('13.5.2026', 'Fair Collection AS', -236.15, 'ting');
  add('13.5.2026', 'EasyPark AS', -73.60, 'bil');
  add('13.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -482.10, 'mat');
  add('12.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -442.10, 'mat');
  add('11.5.2026', 'Google Play Apps', -59.00, 'ting');
  add('11.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -152.70, 'mat');
  add('11.5.2026', 'Fair Collection AS', -443.08, 'ting');
  add('11.5.2026', 'If Skadeforsikring NUF', -159.00, 'faste');
  add('11.5.2026', 'APPLE.COM/BILL', -12.00, 'abonoment');
  add('11.5.2026', 'HOLMLIA TORG AS HOLMLIA SENT OSLO', -122.65, 'mat');
  add('11.5.2026', 'SpotifySE', -139.00, 'abonoment');
  add('11.5.2026', 'PLANTASJEN KOTE GENERAL RUGE OSLO', -419.50, 'hytte');
  add('11.5.2026', 'MENY HOLMLIA HOLMLIA SENT OSLO', -957.24, 'mat');
  add('11.5.2026', 'FLUGGER AS 045 EKEBERGVN 31 OSLO', -711.20, 'ting');
  add('8.5.2026', 'SVEA*BIKESHOP.NO', -978.00, 'ting');
  add('8.5.2026', 'Google YouTube', -169.00, 'abonoment');
  add('8.5.2026', 'APPLE.COM/BILL', -109.00, 'abonoment');
  add('8.5.2026', 'Vipps*Skilthandelen.no', -298.00, 'ting');
  add('8.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -400.46, 'mat');
  add('7.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -215.40, 'mat');
  add('5.5.2026', 'REMA HOLMLIA HOLMLIAVEIEN OSLO', -139.60, 'mat');
  add('5.5.2026', 'NORDEA LØNN INNBETALING', 38420.00, 'lonn');
  add('4.5.2026', 'CIRCLE K KIRKEVEIEN OSLO', -892.30, 'bil');
  add('4.5.2026', 'KIWI HOLMLIA', -187.50, 'mat');
  add('3.5.2026', 'NETFLIX.COM', -149.00, 'abonoment');
  add('3.5.2026', 'XXL SPORT & VILLMARK', -2487.00, 'aktivitet');
  add('2.5.2026', 'IKEA SLEPENDEN', -1456.20, 'ting');
  add('2.5.2026', 'VINMONOPOLET 246', -428.00, 'mat-ute');
  add('1.5.2026', 'STRØM FORTUM', -1287.00, 'bolig');
  add('1.5.2026', 'TELIA MOBIL', -449.00, 'faste');
  add('1.5.2026', 'GET FIBER', -799.00, 'faste');
  add('1.5.2026', 'DNB FORSIKRING', -1240.00, 'faste');
  add('1.5.2026', 'STO AVDRAG SPAREKONTO', -2000.00, 'sparing');
  add('30.4.2026', 'RUSTAD BAKERI', -67.00, 'mat');
  add('29.4.2026', 'PEPPES PIZZA HOLMLIA', -589.00, 'mat-ute');
  add('28.4.2026', 'STRØMSGODSET BILLETT', -380.00, 'aktivitet');
  add('27.4.2026', 'CAFÉ STEEN & STRØM', -148.50, 'mat-ute');
  add('26.4.2026', 'CLAS OHLSON STORO', -349.00, 'ting');
  add('25.4.2026', 'SATS HOLMLIA', -699.00, 'aktivitet');
  add('22.4.2026', 'BYGGER\'N HOLMLIA', -1289.50, 'hytte');
  add('19.4.2026', 'VITUSAPOTEK', -287.40, 'faste');
  add('18.4.2026', 'BABYSHOP.NO', -698.00, 'ting', ['barn']);
  add('15.4.2026', 'SBANKEN TERMINBELØP', -2032.00, 'kreditt', ['lan'], true);
  return txns;
}

const TRANSACTIONS = makeTransactions();

// Category targets (budget)
const CATEGORY_TARGETS = {
  'ting': 4000,
  'mat': 6500,
  'mat-ute': 1200,
  'bil': 1500,
  'faste': 4200,
  'abonoment': 800,
  'aktivitet': 2000,
  'kreditt': 2032,
  'bolig': 17500,
  'ferie': 1500,
  'sparing': 3000,
  'hytte': 1000,
};

// Compute aggregates for current period
function computeCategorySummaries(txns, period = '2026-05') {
  const sums = {};
  CATEGORIES.forEach(c => { sums[c.id] = { ...c, amount: 0, target: CATEGORY_TARGETS[c.id] || null, count: 0 }; });
  txns.filter(t => isInPeriod(t.date, period)).forEach(t => {
    if (!sums[t.categoryId]) return;
    sums[t.categoryId].amount += t.amount;
    sums[t.categoryId].count += 1;
  });
  return Object.values(sums);
}

function isInPeriod(date, period) {
  // date format: "D.M.YYYY", period format "2026-05"
  const [d, m, y] = date.split('.');
  const pY = parseInt(period.slice(0, 4));
  const pM = parseInt(period.slice(5));
  return parseInt(y) === pY && parseInt(m) === pM;
}

function dateToTs(date) {
  const [d, m, y] = date.split('.');
  return new Date(parseInt(y), parseInt(m) - 1, parseInt(d)).getTime();
}

// ---------- Accounts ----------
const ACCOUNTS = [
  { id: 'a1', name: 'Brukskonto', provider: 'Sparebank1 Østlandet', accountNumber: '1800 23 45678', balance: 45523, lastSync: '15.5.2026 14:23' },
  { id: 'a2', name: 'Sparekonto', provider: 'Sparebank1 Østlandet', accountNumber: '1800 23 99012', balance: 187340, lastSync: '15.5.2026 14:23' },
  { id: 'a3', name: 'BSU', provider: 'DNB', accountNumber: '7058 11 22334', balance: 96420, lastSync: '15.5.2026 09:11' },
];

// ---------- Loans ----------
const LOANS = [
  {
    id: 'l1', name: 'Boliglån Holmlia',
    provider: 'Sparebank1 Østlandet',
    initialPrincipal: 3400000,
    interestRate: 5.49,
    monthlyPayment: 18420,
    termMonths: 300,
    monthsPaid: 47,
    paidPrincipal: 312800,
    paidInterest: 553400,
    paidFees: 4200,
    remainingPrincipal: 3087200,
    remainingInterest: 1820000,
    remainingFees: 12000,
  },
  {
    id: 'l2', name: 'Billån Volvo XC60',
    provider: 'Santander Consumer Bank',
    initialPrincipal: 420000,
    interestRate: 6.95,
    monthlyPayment: 7280,
    termMonths: 84,
    monthsPaid: 22,
    paidPrincipal: 92400,
    paidInterest: 65800,
    paidFees: 800,
    remainingPrincipal: 327600,
    remainingInterest: 124200,
    remainingFees: 1500,
  },
  {
    id: 'l3', name: 'Studielån',
    provider: 'Lånekassen',
    initialPrincipal: 280000,
    interestRate: 4.12,
    monthlyPayment: 2940,
    termMonths: 120,
    monthsPaid: 58,
    paidPrincipal: 124700,
    paidInterest: 45800,
    paidFees: 0,
    remainingPrincipal: 155300,
    remainingInterest: 28400,
    remainingFees: 0,
  },
];

// ---------- Reports ----------
const REPORTS = [
  { id: 'r1', name: 'Sparerate', expression: 'alle-inntekter - alle-utgifter', chartType: 'bar' },
  { id: 'r2', name: 'Boligkostnader', expression: 'bolig + faste utgifter', chartType: 'bar' },
  { id: 'r3', name: 'Matforbruk total', expression: 'mat + mat ute', chartType: 'waterfall' },
  { id: 'r4', name: 'Variable kostnader', expression: 'ting + aktivitet + hytte + ferie', chartType: 'bar' },
];

// Per-month aggregates for trend charts (Jan–May 2026)
const MONTHLY_HISTORY = [
  { month: '2026-01', label: 'Jan', income: 36800, expense: -32100, mat: -7820, bil: -2100, faste: -4100, ting: -5800, bolig: -17580 },
  { month: '2026-02', label: 'Feb', income: 36800, expense: -29400, mat: -6400, bil: -1980, faste: -4150, ting: -3200, bolig: -17400 },
  { month: '2026-03', label: 'Mar', income: 36800, expense: -34800, mat: -7100, bil: -2400, faste: -4200, ting: -7400, bolig: -17500 },
  { month: '2026-04', label: 'Apr', income: 36800, expense: -31200, mat: -6800, bil: -2200, faste: -4150, ting: -4800, bolig: -17460 },
  { month: '2026-05', label: 'Mai', income: 38420, expense: -41280, mat: -7300, bil: -1450, faste: -4170, ting: -3340, bolig: -17500 },
];

Object.assign(window, {
  CATEGORIES, TAGS, TRANSACTIONS, CATEGORY_TARGETS,
  ACCOUNTS, LOANS, REPORTS, MONTHLY_HISTORY,
  computeCategorySummaries, isInPeriod, dateToTs,
});
