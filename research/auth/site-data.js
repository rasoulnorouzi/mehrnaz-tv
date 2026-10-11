const DB_NAME = "yascodeDB";
const STORE = "movies";
const JSON_URL = YAS_SEARCH.json_url;
const VERSION = YAS_SEARCH.version;

function openDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.open(DB_NAME, 1);

    req.onupgradeneeded = e => {
      const db = e.target.result;
      if (!db.objectStoreNames.contains(STORE)) {
        db.createObjectStore(STORE);
      }
    };

    req.onsuccess = e => {
      const db = e.target.result;
      db.onversionchange = () => db.close();
      resolve(db);
    };

    req.onerror = () => reject(req.error || new Error("openDB failed"));
    req.onblocked = () => reject(new Error("openDB blocked"));
  });
}

function deleteDB() {
  return new Promise((resolve, reject) => {
    const req = indexedDB.deleteDatabase(DB_NAME);
    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error || new Error("deleteDatabase failed"));
    req.onblocked = () => reject(new Error("deleteDatabase blocked"));
  });
}

function getData(db) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readonly");
    const store = tx.objectStore(STORE);
    const req = store.get("data");

    req.onsuccess = () => resolve(req.result);
    req.onerror = () => reject(req.error || new Error("get failed"));
    tx.onerror = () => reject(tx.error || new Error("readonly transaction failed"));
  });
}

function putData(db, data) {
  return new Promise((resolve, reject) => {
    const tx = db.transaction(STORE, "readwrite");
    const store = tx.objectStore(STORE);
    const req = store.put(data, "data");

    req.onsuccess = () => resolve();
    req.onerror = () => reject(req.error || new Error("put failed"));
    tx.onerror = () => reject(tx.error || new Error("readwrite transaction failed"));
  });
}

async function fetchData() {
  const res = await fetch(JSON_URL + "?v=" + encodeURIComponent(VERSION), { cache: "no-store" });
  if (!res.ok) {
    throw new Error("HTTP " + res.status);
  }
  return await res.json();
}

async function loadSiteData() {
  const savedVersion = localStorage.getItem("yas_search_version");

  if (savedVersion != VERSION) {
    try {
      await deleteDB();
    } catch (e) {}
    localStorage.setItem("yas_search_version", VERSION);
  }

  try {
    const db = await openDB();

    try {
      const cached = await getData(db);
      if (cached) {
        return cached;
      }
    } catch (e) {}

    const data = await fetchData();

    try {
      await putData(db, data);
    } catch (e) {}

    return data;
  } catch (e) {
    return await fetchData();
  }
}
