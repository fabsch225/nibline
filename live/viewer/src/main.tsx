import { createRoot } from "react-dom/client";
import { useEffect, useState } from "react";
import { Live } from "./Live";

/** Relay address: set at build time (VITE_RELAY_URL); `?relay=` overrides it (tests, self-hosting). */
const RELAY = new URLSearchParams(location.search).get("relay") ?? (import.meta.env.VITE_RELAY_URL as string | undefined) ?? "";
const CODE = /^[A-Z]{5}$/;

const codeFromHash = () => decodeURIComponent(location.hash.slice(1)).trim().toUpperCase();

function App() {
  const [code, setCode] = useState(codeFromHash());
  useEffect(() => {
    const on = () => setCode(codeFromHash());
    window.addEventListener("hashchange", on);
    return () => window.removeEventListener("hashchange", on);
  }, []);
  if (!RELAY) return <Message text="This page wasn't built with a relay address." />;
  if (CODE.test(code)) return <Live code={code} relay={RELAY} />;
  return <Join initial={code} />;
}

function Join({ initial }: { initial: string }) {
  const [v, setV] = useState(initial.replace(/[^A-Za-z]/g, "").slice(0, 5).toUpperCase());
  const ok = CODE.test(v);
  return (
    <div className="join">
      <style>{`
        .join { height: 100%; display: grid; place-items: center; padding: 16px; box-sizing: border-box; }
        .join form { display: grid; gap: 14px; width: min(360px, 100%); text-align: center; }
        .join h1 { font-size: 22px; font-weight: 600; margin: 0; }
        .join p { margin: 0; opacity: .7; font-size: 15px; line-height: 1.4; }
        .join input { font: 600 34px/1 ui-monospace, "SF Mono", Menlo, monospace; letter-spacing: .35em; text-align: center; text-transform: uppercase;
          padding: 14px 0 14px .35em; border-radius: 12px; border: 1.5px solid #c9c9d2; background: transparent; color: inherit; width: 100%; box-sizing: border-box; }
        .join input:focus { outline: none; border-color: #6965db; }
        .join button { font: 600 16px system-ui; padding: 12px; border-radius: 10px; border: 0; background: #6965db; color: white; cursor: pointer; }
        .join button:disabled { opacity: .4; cursor: default; }
        @media (prefers-color-scheme: dark) { .join input { border-color: #3a3a44; } }
      `}</style>
      <form onSubmit={(e) => { e.preventDefault(); if (ok) location.hash = v; }}>
        <h1>Inkcalidraw Live</h1>
        <p>Enter the code shown on your tablet under Live.</p>
        <input aria-label="Live code" autoFocus autoComplete="off" autoCapitalize="characters" spellCheck={false} maxLength={5}
          value={v} onChange={(e) => setV(e.target.value.replace(/[^A-Za-z]/g, "").slice(0, 5).toUpperCase())} />
        <button disabled={!ok}>Watch</button>
      </form>
    </div>
  );
}

function Message({ text }: { text: string }) {
  return <div style={{ height: "100%", display: "grid", placeItems: "center", padding: 16 }}>{text}</div>;
}

createRoot(document.getElementById("root")!).render(<App />);
