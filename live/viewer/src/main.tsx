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
        .join { height: 100%; display: grid; place-items: center; padding: 16px; box-sizing: border-box;
          background-image: repeating-linear-gradient(to bottom, transparent 0 31px, var(--rule) 31px 32px); }
        .join form { display: grid; gap: 16px; width: min(400px, 100%); }
        .join h1 { margin: 0 0 8px; font-size: 40px; line-height: 1; font-weight: 900; font-stretch: 125%; letter-spacing: -0.05em; }
        .join h1 a { text-decoration: none; color: inherit; }
        .join p { margin: 0; font-size: 17px; }
        .join input { font: 600 34px/1 "Martian Mono", ui-monospace, monospace; font-stretch: 112%; letter-spacing: .3em; text-align: center; text-transform: uppercase;
          padding: 16px 0 16px .3em; border-radius: 16px; border: 2.5px solid #000; background: #fff; color: inherit; width: 100%; box-sizing: border-box; }
        .join input:focus { outline: none; border-color: var(--orange); }
        .join input::placeholder { color: #c9c9d0; }
        .join button { font: 800 18px "Archivo", system-ui; height: 60px; border-radius: 999px; border: 0; background: #000; color: #fff; cursor: pointer; }
        .join button:not(:disabled):hover { background: var(--orange); color: #000; }
        .join button:disabled { background: #e4e4e8; color: #8a8a92; cursor: default; }
        @media (prefers-color-scheme: dark) {
          .join input { border-color: #f2f2f4; background: #000; } .join input::placeholder { color: #3a3a42; }
          .join button { background: #f2f2f4; color: #000; } .join button:disabled { background: #1c1c21; color: #5d5d66; }
        }
      `}</style>
      <form onSubmit={(e) => { e.preventDefault(); if (ok) location.hash = v; }}>
        <h1><a href="../">inkcalidraw</a></h1>
        <p>Enter the five-letter code shown under Live on the tablet.</p>
        <input aria-label="Live code" autoFocus autoComplete="off" autoCapitalize="characters" spellCheck={false} maxLength={5}
          placeholder="ABCDE" value={v} onChange={(e) => setV(e.target.value.replace(/[^A-Za-z]/g, "").slice(0, 5).toUpperCase())} />
        <button disabled={!ok}>Watch</button>
      </form>
    </div>
  );
}

function Message({ text }: { text: string }) {
  return <div style={{ height: "100%", display: "grid", placeItems: "center", padding: 16 }}>{text}</div>;
}

createRoot(document.getElementById("root")!).render(<App />);
