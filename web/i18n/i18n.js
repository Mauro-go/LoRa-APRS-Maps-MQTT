(() => {
"use strict";

const STORAGE_KEY = "lora-aprs-language";
const SUPPORTED = ["it", "en"];

const exact = {
    "APRS Station Map via MQTT": "APRS Station Map via MQTT",
    "Ricevitori": "Receivers",
    "Ricevitori ▾": "Receivers ▾",
    "📡 Ricevitori ▾": "📡 Receivers ▾",
    "Ultima ora": "Last hour",
    "Ultime 4 ore": "Last 4 hours",
    "Ultime 8 ore": "Last 8 hours",
    "Ultime 12 ore": "Last 12 hours",
    "Ultime 24 ore": "Last 24 hours",
    "Ultima settimana": "Last week",
    "Connessione...": "Connecting...",
    "Aggiornamento...": "Updating...",
    "Errore API": "API error",
    "Stazioni ricevute direttamente": "Stations received directly",
    "Caricamento ricevitore...": "Loading receiver...",
    "Caricamento stazione...": "Loading station...",
    "Mappa": "Map",
    "← Mappa": "← Map",
    "Attività APRS": "APRS activity",
    "Pacchetti trasmessi": "Transmitted packets",
    "Pacchetti ricevuti": "Received packets",
    "Stazioni ricevute direttamente": "Stations received directly",
    "Stazioni ricevute via": "Stations received via",
    "Ricevuto direttamente": "Received directly",
    "Ricevuto via": "Received via",
    "Diretto": "Direct",
    "Ultimo pacchetto": "Last packet",
    "Ultimo ascolto": "Last heard",
    "Stazione": "Station",
    "Stazioni": "Stations",
    "Ricevitore": "Receiver",
    "Ricevitori configurati": "Configured receivers",
    "Pacchetti": "Packets",
    "Pacchetti totali": "Total packets",
    "Stazioni uniche": "Unique stations",
    "Pacchetti ultime 24h": "Packets in last 24h",
    "Stazioni ricevute per ricevitore": "Stations heard by receiver",
    "Ultimi pacchetti": "Latest packets",
    "Ora": "Time",
    "Packet": "Packet",
    "Attivo": "Active",
    "In attesa": "Waiting",
    "Mappa Copertura": "Coverage Map",
    "🗺️ Mappa Copertura": "🗺️ Coverage Map",
    "Monitoraggio ricevitori LoRa APRS via MQTT": "LoRa APRS receiver monitoring via MQTT",
    "Temperatura": "Temperature",
    "Umidità": "Humidity",
    "Pressione": "Pressure",
    "Direzione vento": "Wind direction",
    "Vento": "Wind",
    "Raffica": "Wind gust",
    "Pioggia 1 ora": "Rain 1 hour",
    "Pioggia 24 ore": "Rain 24 hours",
    "Pioggia oggi": "Rain today",
    "Ultimo aggiornamento": "Last update",
    "Nessun ricevitore": "No receivers",
    "Nessun ricevitore configurato": "No receiver configured",
    "Nessun dato": "No data",
    "Nessun dato disponibile": "No data available",
    "Nessuna ricezione diretta": "No direct reception",
    "Nessuna ricezione via": "No relayed reception",
    "Nessun pacchetto": "No packets",
    "Copertura": "Coverage",
    "Online": "Online",
    "Home": "Home",
    "Dashboard": "Dashboard",
    "VIA": "VIA",
    "volta": "time",
    "volte": "times",
    "ultima": "last",
    "direttamente": "directly"
};

const attributes = {
    "Periodo delle stazioni ascoltate": "Station time period"
};

const originals = new WeakMap();
let language = detectLanguage();
let applying = false;

function detectLanguage() {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (SUPPORTED.includes(saved)) return saved;
    const browser = String(navigator.language || "").toLowerCase();
    return browser.startsWith("it") ? "it" : "en";
}

function translateString(value) {
    if (language !== "en" || !value) return value;

    const leading = value.match(/^\s*/)?.[0] || "";
    const trailing = value.match(/\s*$/)?.[0] || "";
    const core = value.trim();

    if (!core) return value;
    if (Object.prototype.hasOwnProperty.call(exact, core)) {
        return leading + exact[core] + trailing;
    }

    let result = core;

    const replacements = [
        [/^(\d+)\s+stazioni$/i, "$1 stations"],
        [/^(\d+)\s+stazione$/i, "$1 station"],
        [/^(\d+)\s+volte$/i, "$1 times"],
        [/^(\d+)\s+volta$/i, "$1 time"],
        [/^ultima:\s*/i, "last: "],
        [/^Ultimo aggiornamento:\s*/i, "Last update: "],
        [/^Ricevuto direttamente da\s+/i, "Received directly by "],
        [/^Ricevuto da\s+(.+?)\s+via\s+/i, "Received by $1 via "],
        [/^Pacchetti transitati via\s+/i, "Packets relayed via "],
        [/^Copertura\s+/i, "Coverage "],
        [/^Errore:\s*/i, "Error: "],
        [/^Posizione del ricevitore\s+/i, "Receiver position "],
        [/\s+non disponibile\.$/i, " unavailable."],
        [/^Nessun ricevitore configurato$/i, "No receiver configured"],
        [/^Impossibile collegarsi all'API LoRa APRS.*$/i, "Unable to connect to the LoRa APRS API"]
    ];

    for (const [pattern, replacement] of replacements) {
        if (pattern.test(result)) {
            result = result.replace(pattern, replacement);
        }
    }

    return leading + result + trailing;
}

function processTextNode(node) {
    if (!originals.has(node)) {
        originals.set(node, node.nodeValue);
    }
    const original = originals.get(node);
    node.nodeValue = language === "it"
        ? original
        : translateString(original);
}

function processElement(el) {
    if (!(el instanceof Element)) return;

    for (const attr of ["title", "placeholder", "aria-label"]) {
        if (!el.hasAttribute(attr)) continue;
        const key = "attr:" + attr;
        let data = originals.get(el);
        if (!data || typeof data !== "object") data = {};
        if (!(key in data)) data[key] = el.getAttribute(attr);
        originals.set(el, data);
        const original = data[key];
        el.setAttribute(
            attr,
            language === "it"
                ? original
                : (attributes[original] || translateString(original))
        );
    }

    if (el.tagName === "TITLE") {
        document.title = language === "en"
            ? document.title
                .replace(/^Stazione APRS - Dettaglio$/i, "APRS Station - Details")
                .replace(/^Copertura /i, "Coverage ")
            : document.title;
    }
}

function translateTree(root = document.body) {
    if (!root) return;
    applying = true;

    if (root.nodeType === Node.TEXT_NODE) {
        processTextNode(root);
    } else {
        processElement(root);
        const walker = document.createTreeWalker(
            root,
            NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT
        );
        let node;
        while ((node = walker.nextNode())) {
            if (node.nodeType === Node.TEXT_NODE) processTextNode(node);
            else processElement(node);
        }
    }

    document.documentElement.lang = language;
    updateSelector();
    applying = false;
}

function updateSelector() {
    const select = document.getElementById("loraLanguageSelect");
    if (select) select.value = language;
}

function setLanguage(next) {
    if (!SUPPORTED.includes(next)) return;
    language = next;
    localStorage.setItem(STORAGE_KEY, language);

    // Dynamic HTML may have been created while another language was active.
    // Reloading guarantees every JS-generated label is rendered from its
    // original Italian source and then translated consistently.
    location.reload();
}

function addSelector() {
    if (document.getElementById("loraLanguageSelect")) return;

    const wrap = document.createElement("div");
    wrap.id = "loraLanguageSwitcher";
    wrap.innerHTML = `
        <span aria-hidden="true">🌐</span>
        <select id="loraLanguageSelect" aria-label="Language">
            <option value="it">Italiano</option>
            <option value="en">English</option>
        </select>
    `;

    const style = document.createElement("style");
    style.textContent = `
        #loraLanguageSwitcher {
            position: fixed;
            right: 12px;
            bottom: 12px;
            z-index: 10000;
            display: flex;
            align-items: center;
            gap: 5px;
            padding: 5px 7px;
            background: rgba(25,25,25,.92);
            border: 1px solid #555;
            border-radius: 6px;
            box-shadow: 0 2px 8px rgba(0,0,0,.35);
            font-family: Arial, Helvetica, sans-serif;
            font-size: 11px;
            color: #fff;
        }
        #loraLanguageSelect {
            border: 0;
            outline: 0;
            background: #333;
            color: #fff;
            padding: 3px 5px;
            border-radius: 4px;
            font-size: 11px;
            cursor: pointer;
        }
        @media (max-width: 700px) {
            #loraLanguageSwitcher {
                right: 7px;
                bottom: 7px;
                padding: 4px 5px;
            }
        }
    `;
    document.head.appendChild(style);
    document.body.appendChild(wrap);

    const select = document.getElementById("loraLanguageSelect");
    select.value = language;
    select.addEventListener("change", event => setLanguage(event.target.value));
}

function start() {
    addSelector();
    translateTree(document.body);

    const observer = new MutationObserver(mutations => {
        if (applying) return;
        for (const mutation of mutations) {
            for (const node of mutation.addedNodes) {
                if (
                    node.nodeType === Node.ELEMENT_NODE ||
                    node.nodeType === Node.TEXT_NODE
                ) {
                    translateTree(node);
                }
            }
            if (
                mutation.type === "characterData" &&
                mutation.target.nodeType === Node.TEXT_NODE
            ) {
                // A program script has replaced the text; treat this new value
                // as the new Italian source for this node.
                originals.set(mutation.target, mutation.target.nodeValue);
                translateTree(mutation.target);
            }
        }
    });

    observer.observe(document.body, {
        childList: true,
        subtree: true,
        characterData: true
    });

    window.LoRaAPRSI18n = {
        getLanguage: () => language,
        setLanguage,
        locale: () => language === "it" ? "it-IT" : "en-GB",
        t: value => language === "it" ? value : translateString(value)
    };
}

if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, {once:true});
} else {
    start();
}
})();
