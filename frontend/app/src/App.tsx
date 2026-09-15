import { Route, Routes } from 'react-router-dom';
import SystemStatusPage from './pages/SystemStatusPage';

/**
 * Route shell. Pages arrive per milestone: Catalog (M1), Policy Builder and
 * Simulator (M4), Enforcement (M5-M7), Audit (M8).
 */
export default function App() {
  return (
    <div className="min-h-screen bg-primary text-primary font-body">
      <Routes>
        <Route path="/" element={<SystemStatusPage />} />
      </Routes>
    </div>
  );
}
