(function () {
  let data = window.MGRADING_DATA || { records: [], summary: {}, labels: [], dates: [], sessions: [] };
  let dataMode = "static";
  const state = {
    date: "all",
    session: "all",
    label: "all",
    minConfidence: 50,
    search: "",
  };

  const els = {
    dateFilter: document.getElementById("dateFilter"),
    sessionFilter: document.getElementById("sessionFilter"),
    labelFilter: document.getElementById("labelFilter"),
    confidenceFilter: document.getElementById("confidenceFilter"),
    confidenceValue: document.getElementById("confidenceValue"),
    searchFilter: document.getElementById("searchFilter"),
    sourceFreshness: document.getElementById("sourceFreshness"),
    sourcePath: document.getElementById("sourcePath"),
    kpiTotal: document.getElementById("kpiTotal"),
    kpiSessions: document.getElementById("kpiSessions"),
    kpiAvgConf: document.getElementById("kpiAvgConf"),
    kpiCrops: document.getElementById("kpiCrops"),
    labelChart: document.getElementById("labelChart"),
    confidenceChart: document.getElementById("confidenceChart"),
    dateCards: document.getElementById("dateCards"),
    dateChart: document.getElementById("dateChart"),
    gallery: document.getElementById("gallery"),
    recordsTable: document.getElementById("recordsTable"),
    exportCsv: document.getElementById("exportCsv"),
    dialog: document.getElementById("detailDialog"),
    detailTitle: document.getElementById("detailTitle"),
    detailBody: document.getElementById("detailBody"),
    closeDialog: document.getElementById("closeDialog"),
  };

  function fmtInt(value) {
    return new Intl.NumberFormat("id-ID").format(value || 0);
  }

  function fmtPct(value) {
    return `${Math.round((value || 0) * 1000) / 10}%`;
  }

  function unique(values) {
    return [...new Set(values.filter(Boolean))].sort();
  }

  function option(value, label) {
    const node = document.createElement("option");
    node.value = value;
    node.textContent = label;
    return node;
  }

  function resetSelect(select) {
    while (select.firstChild) {
      select.removeChild(select.firstChild);
    }
  }

  function setupFilters() {
    const params = new URLSearchParams(window.location.search);
    state.date = params.get("date") || "all";
    state.session = params.get("session") || "all";
    state.label = params.get("label") || "all";
    state.minConfidence = Number(params.get("minConfidence") || 50);
    state.search = params.get("q") || "";

    resetSelect(els.dateFilter);
    resetSelect(els.sessionFilter);
    resetSelect(els.labelFilter);

    els.dateFilter.appendChild(option("all", "Semua tanggal"));
    unique(data.records.map((r) => r.createdDate)).forEach((date) => {
      els.dateFilter.appendChild(option(date, date));
    });

    els.sessionFilter.appendChild(option("all", "Semua session"));
    unique(data.records.map((r) => r.sessionId)).forEach((session) => {
      els.sessionFilter.appendChild(option(session, session));
    });

    els.labelFilter.appendChild(option("all", "Semua label"));
    unique(data.records.map((r) => r.label)).forEach((label) => {
      els.labelFilter.appendChild(option(label, label));
    });

    els.dateFilter.value = state.date;
    els.sessionFilter.value = state.session;
    els.labelFilter.value = state.label;
    els.confidenceFilter.value = state.minConfidence;
    els.confidenceValue.textContent = `${state.minConfidence}%`;
    els.searchFilter.value = state.search;

    if (!els.dateFilter.dataset.bound) {
      els.dateFilter.addEventListener("change", () => { state.date = els.dateFilter.value; syncUrl(); render(); });
      els.sessionFilter.addEventListener("change", () => { state.session = els.sessionFilter.value; syncUrl(); render(); });
      els.labelFilter.addEventListener("change", () => { state.label = els.labelFilter.value; syncUrl(); render(); });
      els.confidenceFilter.addEventListener("input", () => {
      state.minConfidence = Number(els.confidenceFilter.value);
      els.confidenceValue.textContent = `${state.minConfidence}%`;
      syncUrl();
      render();
      });
      els.searchFilter.addEventListener("input", () => {
      state.search = els.searchFilter.value.trim().toLowerCase();
      syncUrl();
      render();
      });
      els.exportCsv.addEventListener("click", exportCsv);
      els.closeDialog.addEventListener("click", () => els.dialog.close());
      els.dateFilter.dataset.bound = "true";
    }
  }

  function syncUrl() {
    const params = new URLSearchParams();
    if (state.date !== "all") params.set("date", state.date);
    if (state.session !== "all") params.set("session", state.session);
    if (state.label !== "all") params.set("label", state.label);
    if (state.minConfidence !== 50) params.set("minConfidence", String(state.minConfidence));
    if (state.search) params.set("q", state.search);
    const next = `${window.location.pathname}${params.toString() ? `?${params}` : ""}`;
    window.history.replaceState({}, "", next);
  }

  function filteredRecords() {
    return data.records.filter((record) => {
      if (state.date !== "all" && record.createdDate !== state.date) return false;
      if (state.session !== "all" && record.sessionId !== state.session) return false;
      if (state.label !== "all" && record.label !== state.label) return false;
      if (record.confidencePct < state.minConfidence) return false;
      if (state.search && !`${record.tagCode} ${record.sessionId} ${record.label}`.toLowerCase().includes(state.search)) return false;
      return true;
    });
  }

  function groupCount(records, key) {
    const map = new Map();
    records.forEach((record) => {
      const value = record[key] || "unknown";
      map.set(value, (map.get(value) || 0) + 1);
    });
    return [...map.entries()].sort((a, b) => b[1] - a[1]).map(([label, count]) => ({ label, count }));
  }

  function confidenceBuckets(records) {
    const buckets = new Map();
    records.forEach((record) => {
      const start = Math.min(90, Math.max(50, Math.floor(record.confidencePct / 10) * 10));
      const label = `${start}-${start + 10}%`;
      buckets.set(label, (buckets.get(label) || 0) + 1);
    });
    return [...buckets.entries()].sort((a, b) => Number(a[0].split("-")[0]) - Number(b[0].split("-")[0])).map(([label, count]) => ({ label, count }));
  }

  function dateSeries(records) {
    const map = new Map();
    records.forEach((record) => map.set(record.createdDate, (map.get(record.createdDate) || 0) + 1));
    return [...map.entries()].sort((a, b) => a[0].localeCompare(b[0])).map(([label, count]) => ({ label, count }));
  }

  function renderDateCards(records) {
    const rows = dateSeries(records.length ? records : data.records);
    const baseRows = dateSeries(data.records);
    const sourceRows = state.date === "all" ? baseRows : rows;
    els.dateCards.innerHTML = sourceRows.map((row) => `
      <button class="date-card ${state.date === row.label ? "active" : ""}" type="button" data-date="${row.label}">
        <span>${escapeHtml(row.label)}</span>
        <strong>${fmtInt(row.count)}</strong>
        <span>record deteksi</span>
      </button>
    `).join("");
    els.dateCards.querySelectorAll("button").forEach((button) => {
      button.addEventListener("click", () => {
        state.date = button.dataset.date;
        els.dateFilter.value = state.date;
        syncUrl();
        render();
      });
    });
  }

  function barChart(target, rows, color) {
    if (!rows.length) {
      target.innerHTML = "<p class=\"empty\">Tidak ada data.</p>";
      return;
    }
    const width = Math.max(480, rows.length * 110);
    const height = 250;
    const max = Math.max(...rows.map((r) => r.count), 1);
    const barW = Math.max(32, (width - 80) / rows.length - 18);
    const bars = rows.map((row, index) => {
      const x = 54 + index * ((width - 80) / rows.length);
      const h = Math.max(3, (row.count / max) * 155);
      const y = 190 - h;
      const label = row.label.length > 14 ? `${row.label.slice(0, 13)}...` : row.label;
      return `
        <rect x="${x}" y="${y}" width="${barW}" height="${h}" rx="4" fill="${color}"></rect>
        <text class="bar-value" x="${x + barW / 2}" y="${y - 7}" text-anchor="middle">${row.count}</text>
        <text class="bar-label" x="${x + barW / 2}" y="218" text-anchor="middle">${escapeHtml(label)}</text>
      `;
    }).join("");
    target.innerHTML = `<svg viewBox="0 0 ${width} ${height}" role="img">${bars}<line x1="42" y1="190" x2="${width - 20}" y2="190" stroke="#d6e0df"/></svg>`;
  }

  function renderKpis(records) {
    const sessions = new Set(records.map((r) => r.sessionId)).size;
    const avg = records.length ? records.reduce((sum, record) => sum + record.confidence, 0) / records.length : 0;
    const crops = records.filter((r) => r.cropUrl).length;
    els.kpiTotal.textContent = fmtInt(records.length);
    els.kpiSessions.textContent = fmtInt(sessions);
    els.kpiAvgConf.textContent = fmtPct(avg);
    els.kpiCrops.textContent = fmtInt(crops);
  }

  function renderGallery(records) {
    const sample = records.filter((r) => r.cropUrl || r.frameUrl || r.annotatedUrl).slice(0, 18);
    if (!sample.length) {
      els.gallery.innerHTML = "<p class=\"empty\">Tidak ada gambar untuk filter ini.</p>";
      return;
    }
    els.gallery.innerHTML = sample.map((record) => {
      const src = record.cropUrl || record.annotatedUrl || record.frameUrl;
      return `
        <button class="thumb" type="button" data-id="${record.id}">
          <img src="${src}" alt="${escapeHtml(record.tagCode)}">
          <div><strong>${escapeHtml(record.tagCode)}</strong>${escapeHtml(record.label)} · ${record.confidencePct}%</div>
        </button>
      `;
    }).join("");
    els.gallery.querySelectorAll(".thumb").forEach((button) => {
      button.addEventListener("click", () => showDetail(Number(button.dataset.id)));
    });
  }

  function renderTable(records) {
    const visible = records.slice(0, 200);
    els.recordsTable.innerHTML = visible.map((record) => {
      const img = record.cropUrl || record.annotatedUrl || record.frameUrl;
      return `
        <tr>
          <td><button class="pill" type="button" data-id="${record.id}">${escapeHtml(record.tagCode)}</button></td>
          <td>${escapeHtml(record.createdDate)}<br><span class="pill">${escapeHtml(record.createdTime)}</span></td>
          <td><code>${escapeHtml(record.sessionId)}</code></td>
          <td>${escapeHtml(record.label)}</td>
          <td>${record.confidencePct}%</td>
          <td>${record.seenCount}</td>
          <td>${img ? `<img class="row-img" src="${img}" alt="${escapeHtml(record.tagCode)}">` : "-"}</td>
        </tr>
      `;
    }).join("");
    els.recordsTable.querySelectorAll("button[data-id]").forEach((button) => {
      button.addEventListener("click", () => showDetail(Number(button.dataset.id)));
    });
  }

  function showDetail(id) {
    const record = data.records.find((item) => item.id === id);
    if (!record) return;
    els.detailTitle.textContent = `${record.tagCode} - ${record.label}`;
    const figures = [
      ["Frame", record.frameUrl],
      ["Crop", record.cropUrl],
      ["Annotated", record.annotatedUrl],
    ].map(([label, src]) => `
      <figure>
        ${src ? `<img src="${src}" alt="${label} ${escapeHtml(record.tagCode)}">` : `<div class="missing">Tidak tersedia</div>`}
        <figcaption>${label}</figcaption>
      </figure>
    `).join("");
    els.detailBody.innerHTML = `
      <div class="detail-grid">${figures}</div>
      <table>
        <tbody>
          <tr><td>Session</td><td><code>${escapeHtml(record.sessionId)}</code></td></tr>
          <tr><td>Confidence</td><td>${record.confidencePct}%</td></tr>
          <tr><td>Waktu</td><td>${escapeHtml(record.createdAt)}</td></tr>
          <tr><td>BBox</td><td>${Object.values(record.bbox).map((v) => Number(v).toFixed(3)).join(", ")}</td></tr>
          <tr><td>Fingerprint</td><td><code>${escapeHtml(record.fingerprint || "-")}</code></td></tr>
        </tbody>
      </table>
    `;
    els.dialog.showModal();
  }

  function exportCsv() {
    const rows = filteredRecords();
    const header = ["id", "tag_code", "date", "time", "session_id", "label", "confidence", "seen_count"];
    const lines = [header.join(",")].concat(rows.map((record) => [
      record.id,
      record.tagCode,
      record.createdDate,
      record.createdTime,
      record.sessionId,
      record.label,
      record.confidence,
      record.seenCount,
    ].map(csvCell).join(",")));
    const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = "mgrading_dashboard_export.csv";
    link.click();
    URL.revokeObjectURL(url);
  }

  function csvCell(value) {
    const text = String(value ?? "");
    return /[",\n]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
  }

  function escapeHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (char) => ({
      "&": "&amp;",
      "<": "&lt;",
      ">": "&gt;",
      "\"": "&quot;",
      "'": "&#39;",
    }[char]));
  }

  function render() {
    const records = filteredRecords();
    renderKpis(records);
    barChart(els.labelChart, groupCount(records, "label"), "#0f766e");
    barChart(els.confidenceChart, confidenceBuckets(records), "#d97706");
    barChart(els.dateChart, dateSeries(records), "#15803d");
    renderDateCards(records);
    renderGallery(records);
    renderTable(records);
  }

  async function loadApiDataIfAvailable() {
    try {
      const response = await fetch("/api/dashboard-data", { cache: "no-store" });
      if (!response.ok) return;
      const apiData = await response.json();
      const hasApiRecords = apiData.records && apiData.records.length > 0;
      const hasStaticRecords = data.records && data.records.length > 0;
      if (hasApiRecords || !hasStaticRecords) {
        data = apiData;
        dataMode = "api";
      }
    } catch (error) {
      dataMode = "static";
    }
  }

  async function init() {
    await loadApiDataIfAvailable();
    setupFilters();
    els.sourceFreshness.textContent = `${dataMode === "api" ? "API upload" : "Static import"} · ${data.generatedAt || "-"}`;
    els.sourcePath.innerHTML = `DB: <code>${escapeHtml(data.source?.dbPath || "-")}</code><br>Media: <code>${escapeHtml(data.source?.mediaRoot || "-")}</code>`;
    render();
  }

  init();
})();
