import { Navigate, Route, Routes, useLocation } from "react-router-dom";
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
import WorkspaceSettings from "./pages/WorkspaceSettings";
import Lists from "./pages/Lists";
import EmailEditor from "./pages/EmailEditor";
import TextEditor from "./pages/TextEditor";
import HtmlEditor from "./pages/HtmlEditor";
import Pricing from "./pages/Pricing";
import ForgotPassword from "./pages/ForgotPassword";
import ResetPassword from "./pages/ResetPassword";
import Landing from "./pages/Landing";

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

/* 루트 게이트: 로그인 상태면 콘솔 셸, 아니면 "/"에서만 소개 랜딩을 보여주고
   그 외 콘솔 경로는 로그인으로 보낸다. */
function ShellGate() {
  const { token } = useAuth();
  const { pathname } = useLocation();
  if (token) return <AppShell />;
  return pathname === "/" ? <Landing /> : <Navigate to="/login" replace />;
}
function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<AuthOnly><Login /></AuthOnly>} />
      <Route path="/signup" element={<AuthOnly><Signup /></AuthOnly>} />
      {/* 요금제: 가입 전 방문자도 보는 공개 페이지 — 게이트 없음 */}
      <Route path="/pricing" element={<Pricing />} />
      {/* 비밀번호 재설정: 메일 링크로 진입하므로 게이트 없음 */}
      <Route path="/forgot-password" element={<AuthOnly><ForgotPassword /></AuthOnly>} />
      <Route path="/reset-password" element={<ResetPassword />} />

      {/* App shell (top nav) wraps the primary screens. 비로그인 "/"는 소개 랜딩. */}
      <Route path="/" element={<ShellGate />}>
        <Route index element={<Dashboard />} />
        <Route path="campaigns" element={<Campaigns />} />
        <Route path="campaigns/new" element={<NewCampaign />} />
        <Route path="campaigns/:id" element={<CampaignDetail />} />
        <Route path="templates" element={<Templates />} />
        <Route path="recipients" element={<Recipients />} />
        <Route path="lists" element={<Lists />} />
        <Route path="analytics" element={<Analytics />} />
        <Route path="settings" element={<WorkspaceSettings />} />
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
