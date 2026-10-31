// Kassenbuch web UI - vanilla JS, talks to /api on the same origin.
const state = { meta: null, period: "", bookings: [], editingNr: null, eTexts: [], aTexts: [] };
let origE = [], origA = [];

const $ = (sel) => document.querySelector(sel);
const $$ = (sel) => Array.from(document.querySelectorAll(sel));

const fmt = (n) => new Intl.NumberFormat("de-DE", { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(n || 0));

async function api(path, options = {}) {
  const res = await fetch(path, {
    method: options.method || "GET",
    headers: { "Content-Type": "application/json" },
    body: options.body ? JSON.stringify(options.body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
  return data;
}

function toast(message, kind = "info") {
  const el = $("#toast");
  el.textContent = message;
  el.className = "toast " + kind;
  el.hidden = false;
  setTimeout(() => { el.hidden = true; }, 4000);
}

function periodeKey(dateStr, mode) {
  const [y, m, d] = dateStr.split("-").map(Number);
  const yy = y % 100;
  if (mode === "1111") return String(y);
  if (mode === "0111") return String(m).padStart(2, "0") + String(yy).padStart(2, "0");
  const q = Math.floor((m - 1) / 3) + 1;
  return "4" + q + String(yy).padStart(2, "0");
}

function periodeLabel(p) {
  if (!p) return "alle";
  if (p.length === 4 && p[0] === "4") {
    const q = p[1], year = 2000 + Number(p.slice(2));
    return "Q" + q + " " + year;
  }
  if (p.length === 4) {
    const month = Number(p.slice(0, 2));
    if (month >= 1 && month <= 12) {
      const names = ["Jan","Feb","Mar","Apr","Mai","Jun","Jul","Aug","Sep","Oct","Nov","Dec"];
      return names[month - 1] + " " + (2000 + Number(p.slice(2)));
    }
    return "Jahr " + p;   // yearly assessment (ST_PERIODE = 1111) stores the plain year
  }
  return p;
}

function tax(betrag, schl) {
  const rate = schl === 2 ? state.meta.rateFull : schl === 1 ? state.meta.rateReduced : 0;
  return Math.round((Number(betrag) / (100 + rate) * rate) * 100) / 100;
}

/* ---------------- meta / header ---------------- */

async function loadMeta() {
  const m = await api("/api/meta");
  state.meta = m;
  state.eTexts = [...(m.einnahmeTexte || [])];
  state.aTexts = [...(m.ausgabeTexte || [])];
  if (!origE.length) { origE = [...state.eTexts]; origA = [...state.aTexts]; }
  $("#companyLine").textContent =
    [state.meta.name1, state.meta.name2].filter(Boolean).join(" · ") +
    " — " + state.meta.adresse + " — St.Nr. " + state.meta.steuernummer +
    (state.meta.email ? " — " + state.meta.email : "");
  $("#modeBadge").textContent = "Veranlagung: " + state.meta.periodeModeLabel;

  const select = $("#periodSelect");
  select.innerHTML = '<option value="">Alle Perioden</option>' +
    state.meta.periods.map((p) => `<option value="${p}">${periodeLabel(p)}</option>`).join("");
  if (state.meta.periods.length && !state.period) state.period = state.meta.periods[state.meta.periods.length - 1];
  select.value = state.period;

  fillTextCatalogue();
}

function fillTextCatalogue() {
  const art = $("#fArt").value;
  const list = (art === "A" ? state.aTexts : state.eTexts).filter((t) => t);
  $("#fTextSelect").innerHTML = list.map((t) => `<option value="${escapeHtml(t)}">${escapeHtml(t)}</option>`).join("");
}

function renderCatalogue() {
  const rows = (list, set) => list.map((t, i) => `
    <div class="textrow">
      <input type="text" maxlength="30" value="${escapeHtml(t)}" placeholder="(leerer Slot)" data-set="${set}" data-idx="${i}">
      <button class="link danger" data-remove="${i}" data-set="${set}" title="Slot entfernen">✕</button>
    </div>`).join("") || '<p class="hint">Keine Texte — mit "+ Text" einen anlegen.</p>';
  $("#eTextList").innerHTML = rows(state.eTexts, "e");
  $("#aTextList").innerHTML = rows(state.aTexts, "a");
}

async function saveCatalogue() {
  try {
    const meta = await api("/api/stamm", {
      method: "PUT",
      body: { einnahmeTexte: state.eTexts, ausgabeTexte: state.aTexts },
    });
    state.meta = meta;
    state.eTexts = [...meta.einnahmeTexte];
    state.aTexts = [...meta.ausgabeTexte];
    origE = [...state.eTexts];
    origA = [...state.aTexts];
    renderCatalogue();
    fillTextCatalogue();
    toast("Buchungstexte gespeichert", "ok");
  } catch (err) { toast(err.message, "error"); }
}

/* ---------------- bookings ---------------- */

async function loadBookings() {
  state.bookings = await api("/api/bookings?period=" + encodeURIComponent(state.period));
  renderBookings();
}

function renderBookings() {
  const tbody = $("#bookingTable tbody");
  tbody.innerHTML = state.bookings.map((b) => `
    <tr data-nr="${b.nr}">
      <td>${b.nr}</td>
      <td>${b.datumDisplay}</td>
      <td class="art ${b.art}">${b.art}</td>
      <td>${escapeHtml(b.text)}</td>
      <td class="num">${fmt(b.betrag)}</td>
      <td class="num">${fmt(b.steuer)}</td>
      <td class="num">${fmt(b.netto)}</td>
      <td>${b.steuerSchluessel}</td>
      <td>${periodeLabel(b.periode)}</td>
      <td class="actions">
        <button class="link" data-edit="${b.nr}">bearbeiten</button>
        <button class="link danger" data-del="${b.nr}">löschen</button>
      </td>
    </tr>`).join("");
  $("#rowCount").textContent = state.bookings.length + " Buchungen · " + periodeLabel(state.period);
}

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function updatePreview() {
  const betrag = Number($("#fBetrag").value || 0);   // Bruttobetrag der neuen Buchung
  const schl = Number($("#fSchl").value);
  const st = tax(betrag, schl);
  $("#prevSteuer").textContent = fmt(st);          // Steuerbetrag
  $("#prevNetto").textContent = fmt(betrag - st);  // Netto = Betrag ohne Steuer
  const datum = $("#fDatum").value;
  $("#prevPeriode").textContent = datum && state.meta
    ? periodeLabel(periodeKey(datum, state.meta.periodeMode))
    : "—";
}

async function submitBooking(e) {
  e.preventDefault();
  const text = $("#fText").value.trim() || $("#fTextSelect").value || "";
  const payload = {
    datum: $("#fDatum").value,
    art: $("#fArt").value,
    text,
    betrag: Number($("#fBetrag").value),
    steuerSchluessel: Number($("#fSchl").value),
  };
  try {
    if (state.editingNr) {
      await api("/api/bookings/" + state.editingNr, { method: "PUT", body: payload });
      toast("Buchung " + state.editingNr + " aktualisiert", "ok");
      state.editingNr = null;
      $("#addForm button[type=submit]").textContent = "Buchen";
    } else {
      const b = await api("/api/bookings", { method: "POST", body: payload });
      toast("Buchung " + b.nr + " gebucht", "ok");
    }
    $("#fBetrag").value = "0.00";
    $("#fText").value = "";
    updatePreview();
    await Promise.all([loadBookings(), loadSummary(), loadMeta(), refreshVisible()]);
    updatePreview();
  } catch (err) {
    toast(err.message, "error");
  }
}

function startEdit(nr) {
  const b = state.bookings.find((x) => x.nr === nr);
  if (!b) return;
  state.editingNr = nr;
  $("#fDatum").value = b.datum;
  $("#fArt").value = b.art;
  $("#fText").value = b.text;
  $("#fBetrag").value = b.betrag.toFixed(2);
  $("#fSchl").value = b.steuerSchluessel;
  fillTextCatalogue();
  updatePreview();
  $("#addForm button[type=submit]").textContent = "Aktualisieren";
  toast("Buchung " + nr + " bearbeiten — Änderungen speichern");
}

async function deleteBooking(nr) {
  if (!confirm("Buchung " + nr + " wirklich löschen?")) return;
  try {
    await api("/api/bookings/" + nr, { method: "DELETE" });
    toast("Buchung " + nr + " gelöscht", "ok");
    await Promise.all([loadBookings(), loadSummary(), refreshVisible()]);
  } catch (err) { toast(err.message, "error"); }
}

/* ---------------- summary cards ---------------- */

async function loadSummary() {
  const s = await api("/api/summary?period=" + encodeURIComponent(state.period));
  $("#summary").innerHTML = `
    <div class="card"><span>Einnahmen brutto</span><strong>${fmt(s.einnahmenBrutto)} €</strong></div>
    <div class="card"><span>Einnahmen netto</span><strong>${fmt(s.einnahmenNetto)} €</strong></div>
    <div class="card"><span>Umsatzsteuer</span><strong>${fmt(s.umsatzsteuer)} €</strong></div>
    <div class="card"><span>Ausgaben netto</span><strong>${fmt(s.ausgabenNetto)} €</strong></div>
    <div class="card"><span>Vorsteuer / MwSt</span><strong>${fmt(s.mehrwertsteuer)} €</strong></div>
    <div class="card saldo"><span>Saldo netto</span><strong>${fmt(s.saldoNetto)} €</strong></div>`;
}

/* ---------------- UST / monthly / jabu ---------------- */

async function loadUst() {
  const box = $("#ustBody");
  try {
    const u = await api("/api/ust?period=" + encodeURIComponent(state.period));
    box.innerHTML = `
      <p class="hint">Zeitraum: <strong>${escapeHtml(periodeLabel(state.period))}</strong></p>
      <table class="kv">
        <tr><td>Summe Erlöse voll (netto)</td><td class="num">${fmt(u.sumFullNetto)} €</td></tr>
        <tr><td>Summe Erlöse ermäßigt (netto)</td><td class="num">${fmt(u.sumReducedNetto)} €</td></tr>
        <tr><td>Bemessungsgrundlage</td><td class="num">${fmt(u.bemessungsgrundlage)} €</td></tr>
        <tr><td>Umsatzsteuer</td><td class="num">${fmt(u.ustSteuer)} €</td></tr>
        <tr><td>abziehbare Vorsteuer</td><td class="num">− ${fmt(u.vorsteuer)} €</td></tr>
        <tr class="total"><td>Zahlbare Umsatzsteuer</td><td class="num">${fmt(u.zahlbar)} €</td></tr>
      </table>`;
  } catch (err) {
    box.innerHTML = `<p class="error">Umsatzsteuer konnte nicht geladen werden: `
      + `${escapeHtml(err.message)}</p>`;
    toast("Umsatzsteuer: " + err.message, "error");
  }
}

async function loadMonthly() {
  const body = $("#monthlyTable tbody");
  try {
    const rows = await api("/api/monthly");
    if (!Array.isArray(rows) || rows.length === 0)
      throw new Error("/api/monthly lieferte keine Zeilen");
    // same as the Kassen-Information report: each month on its own, Gesamt over all
    let sumE = 0, sumA = 0;
    const html = rows.map((r) => {
      sumE += Number(r.einnahmen || 0); sumA += Number(r.ausgaben || 0);
      return `<tr><td>${escapeHtml(String(r.month))}</td>`
        + `<td class="num">${fmt(Number(r.einnahmen || 0))}</td>`
        + `<td class="num">${fmt(Number(r.ausgaben || 0))}</td>`
        + `<td class="num">${fmt(Number(r.ergebnis ?? (r.einnahmen - r.ausgaben)))}</td></tr>`;
    });
    html.push(`<tr class="total"><td>Gesamt</td><td class="num">${fmt(sumE)}</td>`
      + `<td class="num">${fmt(sumA)}</td><td class="num">${fmt(sumE - sumA)}</td></tr>`);
    body.innerHTML = html.join("");
  } catch (err) {
    body.innerHTML = `<tr><td colspan="4">Monatsübersicht konnte nicht geladen werden: `
      + `${escapeHtml(err.message)}</td></tr>`;
    toast("Monatsübersicht: " + err.message, "error");
  }
}

async function loadJabu() {
  const rows = await api("/api/jabu");
  $("#jabuTable tbody").innerHTML = rows.map((r) =>
    `<tr><td>${r.jahr}</td><td class="num">${fmt(r.einnahmen)}</td><td class="num">${fmt(r.ausgaben)}</td><td class="num">${fmt(r.einnahmen - r.ausgaben)}</td></tr>`).join("");
}

async function runYearEnd() {
  if (!confirm("Jahresabschluss: sichern, CSV-Export nach out/bak/, Jahressummen bilden und KABU.DBF leeren?")) return;
  try {
    const r = await api("/api/yearend", { method: "POST" });
    const steps = Array.isArray(r.schritte) ? r.schritte : [];
    $("#yearendResult").innerHTML = `<p class="ok">Jahr ${r.jahr} abgeschlossen.</p>`
      + `<ul class="done">` + steps.map((s) => `<li>${escapeHtml(String(s))}</li>`).join("") + `</ul>`
      + `<p class="hint">CSV-Archiv: <code>${escapeHtml(String(r.csvBackup || ""))}</code> · ` 
      + `DBF-Sicherung: <code>${escapeHtml(String(r.backup || ""))}</code></p>`;
    await Promise.all([loadMeta(), loadBookings(), loadSummary()]);
    refreshVisible();
  } catch (err) { toast(err.message, "error"); }
}

async function runBackup() {
  const out = $("#backupResult");
  try {
    const r = await api("/api/backup", { method: "POST" });
    const steps = Array.isArray(r.schritte) ? r.schritte : [];
    const files = Array.isArray(r.dateien) ? r.dateien : [];
    const bytes = (n) => new Intl.NumberFormat("de-DE").format(Number(n || 0));
    out.innerHTML = `<p class="ok">Backup erstellt: <code>${escapeHtml(String(r.name))}</code></p>`
      + `<ul class="done">` + steps.map((s) => `<li>${escapeHtml(String(s))}</li>`).join("") + `</ul>`
      + `<p class="hint">${files.length} Datei(en): ${files.map((f) => `<code>${escapeHtml(String(f))}</code>`).join(" ")}`
      + ` · ${bytes(r.unkomprimiert)} Bytes unkomprimiert → ${bytes(r.groesse)} Bytes Archiv</p>`
      + `<p class="warnhinweis"><strong>HINWEIS:</strong> <code>${escapeHtml(String(r.name))}</code>`
      + ` aus dem Programmordner <strong>wegkopieren</strong> — die Datei liegt sonst nur`
      + ` neben den Originaldaten und ist kein Backup.</p>`;
    toast("Backup erstellt: " + r.name, "ok");
  } catch (err) {
    out.innerHTML = `<p class="error">${escapeHtml(err.message)}</p>`;
    toast("Backup fehlgeschlagen: " + err.message, "error");
  }
}

/* ---------------- stamm ---------------- */

async function loadStammForm() {
  const m = state.meta;
  $("#sName1").value = m.name1;
  $("#sName2").value = m.name2;
  $("#sStnr").value = m.steuernummer;
  $("#sFinanzamt").value = m.finanzamt;
  $("#sStrasse").value = m.strasse || m.adresse;
  $("#sPlz").value = m.plz || "";
  $("#sOrt").value = m.ort || "";
  $("#sTelefon").value = m.telefon;
  $("#sEmail").value = m.email || "";
  $("#sGewerbe").value = m.gewerbe;
  $("#sRateFull").value = m.rateFull;
  $("#sRateReduced").value = m.rateReduced;
  $("#sMode").value = m.periodeMode;
}

async function saveStamm(e) {
  e.preventDefault();
  try {
    const meta = await api("/api/stamm", {
      method: "PUT",
      body: {
        name1: $("#sName1").value,
        name2: $("#sName2").value,
        steuernummer: $("#sStnr").value,
        finanzamt: $("#sFinanzamt").value,
        strasse: $("#sStrasse").value,
        plz: $("#sPlz").value,
        ort: $("#sOrt").value,
        telefon: $("#sTelefon").value,
        email: $("#sEmail").value,
        gewerbe: $("#sGewerbe").value,
        rateFull: $("#sRateFull").value,
        rateReduced: $("#sRateReduced").value,
        periodeMode: $("#sMode").value,
      },
    });
    state.meta = meta;
    await Promise.all([loadMeta(), loadBookings(), loadSummary()]);
    toast("Stammdaten gespeichert", "ok");
  } catch (err) { toast(err.message, "error"); }
}

/* ---------------- reports ---------------- */

async function runReport() {
  const kind = $("#reportKind").value;
  // Kassen-Information is the yearly overview: it always covers every period,
  // regardless of the period selector in the top bar.
  const period = kind === "info" ? "" : state.period;
  const url = "/api/report/" + kind + "?period=" + encodeURIComponent(period);
  const res = await fetch(url);
  $("#reportOut").textContent = await res.text();
  $("#reportPeriod").textContent = kind === "info"
    ? "alle Perioden (Jahresübersicht)"
    : periodeLabel(period);
  $("#reportDownloadTxt").href = url + "&fmt=dos";
  $("#reportDownloadPdf").href = url + "&fmt=pdf";
}

/* ---------------- data interchange (JSON / CSV) ---------------- */

function exportHref(fmtName) {
  const p = $("#dataPeriod");
  return "/api/export?fmt=" + fmtName + "&period=" + encodeURIComponent(p ? p.value : "");
}

function loadDataView() {
  const sel = $("#dataPeriod");
  sel.innerHTML = "";
  const add = (value, label) => {
    const o = document.createElement("option");
    o.value = value; o.textContent = label;
    sel.appendChild(o);
  };
  add("", "alle Perioden");
  (state.meta && state.meta.periods ? state.meta.periods : []).forEach((p) => add(p, periodeLabel(p)));
  sel.value = state.period || "";
  refreshExportLinks();
}

function refreshExportLinks() {
  $("#exportJson").href = exportHref("json");
  $("#exportCsv").href = exportHref("csv");
}

async function doImport() {
  const file = $("#importFile").files[0];
  if (!file) { toast("Keine Datei ausgewählt", "error"); return; }
  const mode = $("#importMode").value;
  const res = await fetch("/api/import?mode=" + encodeURIComponent(mode), { method: "POST", body: file });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const msg = data.error || ("HTTP " + res.status);
    $("#importOut").textContent = "Fehler: " + msg;
    toast("Import abgebrochen", "error");
    return;
  }
  $("#importOut").textContent = data.nachricht
    + "\nFormat: " + data.format + "  Modus: " + data.mode
    + (data.backup ? "\nSicherungskopie: " + data.backup : "")
    + "\nZiel: " + data.datenbank;
  toast("Import: " + data.nachricht);
  await Promise.all([loadBookings(), loadSummary(), loadMeta(), refreshVisible()]);
}

/* ---------------- wiring ---------------- */

/** the view-dependent figures that follow the selected period, if that view is open */
function refreshVisible() {
  const tab = $$(".tab").find((t) => t.classList.contains("active"));
  if (!tab) return;
  switch (tab.dataset.view) {
    case "ust": loadUst(); break;
    case "monthly": loadMonthly(); break;
    case "reports": runReport(); break;
    case "yearend": loadJabu(); break;
  }
}

function switchView(view) {
  $$(".view").forEach((v) => v.classList.add("hidden"));
  $("#view-" + view).classList.remove("hidden");
  $$(".tab").forEach((t) => t.classList.toggle("active", t.dataset.view === view));
  if (view === "ust") loadUst();
  if (view === "monthly") loadMonthly();
  if (view === "yearend") loadJabu();
  if (view === "stamm") loadStammForm();
  if (view === "reports") runReport();
  if (view === "data") loadDataView();
}

function tickClock() {
  const now = new Date();
  $("#clock").textContent = now.toLocaleDateString("de-DE") + " " + now.toLocaleTimeString("de-DE");
}

document.addEventListener("DOMContentLoaded", async () => {
  try {
    $("#fDatum").value = new Date().toISOString().slice(0, 10);
    await loadMeta();
    await loadBookings();
    await loadSummary();
    fillTextCatalogue();
    updatePreview();
  } catch (err) {
    toast(err.message, "error");
  }

  $$(".tab").forEach((t) => t.addEventListener("click", () => switchView(t.dataset.view)));
  $("#periodSelect").addEventListener("change", async (e) => {
    state.period = e.target.value;
    await Promise.all([loadBookings(), loadSummary()]);
    refreshVisible();
  });
  $("#addForm").addEventListener("submit", submitBooking);
  $("#fArt").addEventListener("change", () => { fillTextCatalogue(); updatePreview(); });
  ["fDatum", "fBetrag", "fSchl"].forEach((id) => $("#" + id).addEventListener("input", updatePreview));
  $("#fTextSelect").addEventListener("change", () => { $("#fText").value = $("#fTextSelect").value; updatePreview(); });
  $("#bookingTable").addEventListener("click", (e) => {
    if (e.target.dataset.edit) startEdit(Number(e.target.dataset.edit));
    if (e.target.dataset.del) deleteBooking(Number(e.target.dataset.del));
  });
  $("#stammForm").addEventListener("submit", saveStamm);
  $("#eTextList").addEventListener("input", (e) => {
    if (e.target.dataset.idx !== undefined) state.eTexts[Number(e.target.dataset.idx)] = e.target.value;
  });
  $("#aTextList").addEventListener("input", (e) => {
    if (e.target.dataset.idx !== undefined) state.aTexts[Number(e.target.dataset.idx)] = e.target.value;
  });
  $("#eTextList").addEventListener("click", (e) => {
    if (e.target.dataset.remove !== undefined) { state.eTexts.splice(Number(e.target.dataset.remove), 1); renderCatalogue(); }
  });
  $("#aTextList").addEventListener("click", (e) => {
    if (e.target.dataset.remove !== undefined) { state.aTexts.splice(Number(e.target.dataset.remove), 1); renderCatalogue(); }
  });
  $("#addEText").addEventListener("click", () => { state.eTexts.push(""); renderCatalogue(); });
  $("#addAText").addEventListener("click", () => { state.aTexts.push(""); renderCatalogue(); });
  $("#catalogueSave").addEventListener("click", saveCatalogue);
  $("#catalogueUndo").addEventListener("click", () => {
    state.eTexts = [...origE]; state.aTexts = [...origA]; renderCatalogue(); fillTextCatalogue();
    toast("Textvorschläge zurückgesetzt");
  });
  renderCatalogue();
  $("#yearendBtn").addEventListener("click", runYearEnd);
  $("#backupBtn").addEventListener("click", runBackup);
  $("#reportRun").addEventListener("click", runReport);
  $("#dataPeriod").addEventListener("change", refreshExportLinks);
  $("#importBtn").addEventListener("click", doImport);
  tickClock();
  setInterval(tickClock, 1000);
});
