import React, { useState } from 'react';
import { useAuth } from './context/AuthContext';
import { LoginView } from './views/LoginView';
import { DirectSaleView } from './views/DirectSaleView';
import { WaiterModeView } from './views/WaiterModeView';
import { CashierView } from './views/CashierView';
import { DashboardView } from './views/DashboardView';
import { 
  LayoutDashboard, 
  ShoppingBag, 
  Utensils, 
  Wallet, 
  LogOut, 
  Menu, 
  X,
  User as UserIcon
} from 'lucide-react';
import { motion, AnimatePresence } from 'motion/react';
import { cn } from './lib/utils';

type View = 'dashboard' | 'direct_sale' | 'waiter_mode' | 'cashier';

const App: React.FC = () => {
  const { user, permissions, loading, logout } = useAuth();
  const [currentView, setCurrentView] = useState<View>('direct_sale');
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);

  if (loading) {
    return (
      <div className="min-h-screen bg-zinc-50 flex items-center justify-center">
        <motion.div 
          animate={{ rotate: 360 }}
          transition={{ repeat: Infinity, duration: 1, ease: "linear" }}
          className="w-12 h-12 border-4 border-emerald-600 border-t-transparent rounded-full"
        />
      </div>
    );
  }

  if (!user) {
    return <LoginView />;
  }

  const isModuleEnabled = (moduleName: string) => {
    if (user.role === 'owner') return true;
    return permissions?.permissions.find(p => p.module === moduleName)?.enabled ?? false;
  };

  const navItems = [
    { id: 'direct_sale', label: 'Venda Rápida', icon: ShoppingBag, color: 'text-emerald-600', bg: 'bg-emerald-50', enabled: true },
    { id: 'waiter_mode', label: 'Restaurante', icon: Utensils, color: 'text-indigo-600', bg: 'bg-indigo-50', enabled: isModuleEnabled('restaurant') },
    { id: 'cashier', label: 'Caixa', icon: Wallet, color: 'text-orange-600', bg: 'bg-orange-50', enabled: isModuleEnabled('smart_pos') },
    { id: 'dashboard', label: 'Dashboard', icon: LayoutDashboard, color: 'text-zinc-600', bg: 'bg-zinc-50', enabled: isModuleEnabled('dashboard') },
  ];

  const renderView = () => {
    switch (currentView) {
      case 'direct_sale': return <DirectSaleView />;
      case 'waiter_mode': return <WaiterModeView />;
      case 'cashier': return <CashierView />;
      case 'dashboard': return <DashboardView />;
      default: return <DirectSaleView />;
    }
  };

  return (
    <div className="h-screen flex bg-zinc-100 overflow-hidden font-sans">
      {/* Sidebar Desktop */}
      <aside className="hidden md:flex w-24 bg-white border-r border-zinc-200 flex-col items-center py-8 gap-8 shadow-xl z-20">
        <div className="w-12 h-12 bg-emerald-600 rounded-2xl flex items-center justify-center shadow-lg shadow-emerald-100">
          <span className="text-white font-black text-xl">S</span>
        </div>
        
        <nav className="flex-1 flex flex-col gap-4">
          {navItems.filter(i => i.enabled).map((item) => (
            <button
              key={item.id}
              onClick={() => setCurrentView(item.id as View)}
              className={cn(
                "w-16 h-16 rounded-2xl flex flex-col items-center justify-center transition-all gap-1",
                currentView === item.id ? cn(item.bg, item.color, "shadow-inner") : "text-zinc-400 hover:bg-zinc-50"
              )}
            >
              <item.icon size={24} />
              <span className="text-[10px] font-bold uppercase tracking-tighter">{item.label.split(' ')[0]}</span>
            </button>
          ))}
        </nav>

        <button 
          onClick={logout}
          className="w-16 h-16 rounded-2xl flex flex-col items-center justify-center text-zinc-400 hover:bg-red-50 hover:text-red-500 transition-all gap-1"
        >
          <LogOut size={24} />
          <span className="text-[10px] font-bold uppercase tracking-tighter">Sair</span>
        </button>
      </aside>

      {/* Mobile Header */}
      <div className="md:hidden fixed top-0 left-0 right-0 h-16 bg-white border-b border-zinc-200 flex items-center justify-between px-4 z-30">
        <button onClick={() => setIsSidebarOpen(true)} className="p-2 text-zinc-600">
          <Menu size={28} />
        </button>
        <span className="font-black text-emerald-600 text-xl tracking-tighter">SmartPOS</span>
        <div className="w-10 h-10 bg-zinc-100 rounded-full flex items-center justify-center">
          <UserIcon size={20} className="text-zinc-600" />
        </div>
      </div>

      {/* Mobile Sidebar Overlay */}
      <AnimatePresence>
        {isSidebarOpen && (
          <>
            <motion.div 
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              onClick={() => setIsSidebarOpen(false)}
              className="fixed inset-0 bg-black/50 z-40 md:hidden"
            />
            <motion.aside
              initial={{ x: '-100%' }}
              animate={{ x: 0 }}
              exit={{ x: '-100%' }}
              className="fixed inset-y-0 left-0 w-72 bg-white z-50 md:hidden flex flex-col p-6"
            >
              <div className="flex justify-between items-center mb-10">
                <span className="font-black text-2xl text-emerald-600">SmartPOS</span>
                <button onClick={() => setIsSidebarOpen(false)} className="p-2 text-zinc-400">
                  <X size={28} />
                </button>
              </div>

              <div className="flex items-center gap-4 mb-10 p-4 bg-zinc-50 rounded-3xl">
                <div className="w-12 h-12 bg-emerald-100 rounded-2xl flex items-center justify-center">
                  <UserIcon className="text-emerald-600" />
                </div>
                <div>
                  <p className="font-bold text-zinc-900">{user.full_name}</p>
                  <p className="text-xs text-zinc-500">{user.email}</p>
                </div>
              </div>

              <nav className="flex-1 space-y-2">
                {navItems.filter(i => i.enabled).map((item) => (
                  <button
                    key={item.id}
                    onClick={() => {
                      setCurrentView(item.id as View);
                      setIsSidebarOpen(false);
                    }}
                    className={cn(
                      "w-full flex items-center gap-4 p-4 rounded-2xl font-bold transition-all",
                      currentView === item.id ? cn(item.bg, item.color) : "text-zinc-500 hover:bg-zinc-50"
                    )}
                  >
                    <item.icon size={24} />
                    {item.label}
                  </button>
                ))}
              </nav>

              <button 
                onClick={logout}
                className="w-full flex items-center gap-4 p-4 rounded-2xl font-bold text-red-500 hover:bg-red-50 transition-all mt-auto"
              >
                <LogOut size={24} />
                Sair do Sistema
              </button>
            </motion.aside>
          </>
        )}
      </AnimatePresence>

      {/* Main Content Area */}
      <main className="flex-1 flex flex-col pt-16 md:pt-0 overflow-hidden">
        {renderView()}
      </main>
    </div>
  );
};

export default App;
