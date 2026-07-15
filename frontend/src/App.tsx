import { Navigate, Route, Routes } from "react-router-dom";
import { AuthProvider, useAuth } from "./outpace/auth";
import AppShell from "./components/AppShell";
import Login from "./pages/Login";
import Signup from "./pages/Signup";
import Dashboard from "./pages/Dashboard";
import Campaigns from "./pages/Campaigns";
import NewCampaign from "./pages/NewCampaign";
import CampaignDetail from "./pages/CampaignDetail";
import Templates from "./pages/Templates";
import Recipients from "./pages/Recipients";
import Analytics from "./pages/Analytics";
import Lists from "./pages/Lists";
import EmailEditor from "./pages/EmailEditor";
import TextEditor from "./pages/TextEditor";
import HtmlEditor from "./pages/HtmlEditor";

/* Gate: send unauthenticated users to /login; render children otherwise. */
function RequireAuth({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  return token ? <>{children}</> : <Navigate to="/login" replace />;
}

/* Auth screens bounce to the dashboard once a session exists. */
function AuthOnly({ children }: { children: React.ReactNode }) {
  const { token } = useAuth();
  return token ? <Navigate to="/" replace /> : <>{children}</>;
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<AuthOnly><Login /></AuthOnly>} />
      <Route path="/signup" element={<AuthOnly><Signup /></AuthOnly>} />

      {/* App shell (top nav) wraps the primary screens. */}
      <Route
        path="/"
        element={
          <RequireAuth>
            <AppShell />
          </RequireAuth>
        }
      >
        <Route index element={<Dashboard />} />
        <Route path="campaigns" element={<Campaigns />} />
        <Route path="campaigns/new" element={<NewCampaign />} />
        <Route path="campaigns/:id" element={<CampaignDetail />} />
        <Route path="templates" element={<Templates />} />
        <Route path="recipients" element={<Recipients />} />
        <Route path="lists" element={<Lists />} />
        <Route path="analytics" element={<Analytics />} />
      </Route>

      {/* Full-screen editors live outside the shell. */}
      <Route path="/editor" element={<RequireAuth><EmailEditor /></RequireAuth>} />
      <Route path="/editor/:id" element={<RequireAuth><EmailEditor /></RequireAuth>} />
      <Route path="/editor/text" element={<RequireAuth><TextEditor /></RequireAuth>} />
      <Route path="/editor/text/:id" element={<RequireAuth><TextEditor /></RequireAuth>} />
      <Route path="/editor/html" element={<RequireAuth><HtmlEditor /></RequireAuth>} />
      <Route path="/editor/html/:id" element={<RequireAuth><HtmlEditor /></RequireAuth>} />

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}

export default function App() {
  return (
    <AuthProvider>
      <AppRoutes />
    </AuthProvider>
  );
}
