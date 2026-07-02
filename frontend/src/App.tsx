import { useEffect, useState } from "react";
import Auth from "./Auth";
import Campaigns from "./tabs/Campaigns";
import Templates from "./tabs/Templates";
import Contacts from "./tabs/Contacts";
import Lists from "./tabs/Lists";

type Tab = "campaigns" | "templates" | "contacts" | "lists";

const TABS: { key: Tab; label: string }[] = [
  { key: "campaigns", label: "캠페인" },
  { key: "templates", label: "템플릿" },
  { key: "contacts", label: "연락처" },
  { key: "lists", label: "리스트" },
];

export default function App() {
  const [token, setToken] = useState<string | null>(() => localStorage.getItem("mail.token"));
  const [email, setEmail] = useState<string | null>(() => localStorage.getItem("mail.email"));
  const [online, setOnline] = useState(false);
  const [tab, setTab] = useState<Tab>("campaigns");

  function logout() {
    localStorage.removeItem("mail.token");
    localStorage.removeItem("mail.email");
    setToken(null);
    setEmail(null);
  }

  function onAuthed(newToken: string, newEmail: string) {
    localStorage.setItem("mail.token", newToken);
    localStorage.setItem("mail.email", newEmail);
    setToken(newToken);
    setEmail(newEmail);
  }

  // Health dot: poll the public health endpoint so the header reflects API availability.
  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    async function check() {
      try {
        const res = await fetch("/api/health");
        if (!cancelled) setOnline(res.ok);
      } catch {
        if (!cancelled) setOnline(false);
      }
    }
    check();
    const id = setInterval(check, 5000);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [token]);

  if (!token) {
    return <Auth onAuthed={onAuthed} />;
  }

  return (
    <main>
      <header className="app-header">
        <h1>
          Mail Platform{" "}
          <span className={online ? "dot online" : "dot offline"}>
            ● api {online ? "online" : "offline"}
          </span>
        </h1>
        <div className="header-right">
          <span className="muted">{email}</span>
          <button type="button" onClick={logout}>
            로그아웃
          </button>
        </div>
      </header>

      <nav className="tab-bar">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            className={tab === t.key ? "tab active" : "tab"}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </nav>

      {tab === "campaigns" && <Campaigns />}
      {tab === "templates" && <Templates />}
      {tab === "contacts" && <Contacts />}
      {tab === "lists" && <Lists />}
    </main>
  );
}
