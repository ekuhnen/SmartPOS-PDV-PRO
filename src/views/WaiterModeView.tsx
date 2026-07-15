import React, { useState, useEffect } from 'react';
import { restaurantService } from '../services/api';
import { Sector, Mesa } from '../types';
import { Users, LayoutGrid, List, Loader2, ChevronRight, Clock } from 'lucide-react';
import { cn } from '../lib/utils';
import { motion } from 'motion/react';
import { ComandaDetail } from '../components/ComandaDetail';

export const WaiterModeView: React.FC = () => {
  const [sectors, setSectors] = useState<Sector[]>([]);
  const [loading, setLoading] = useState(true);
  const [selectedMesa, setSelectedMesa] = useState<Mesa | null>(null);
  const [isDetailOpen, setIsDetailOpen] = useState(false);

  useEffect(() => {
    fetchMesas();
  }, []);

  const fetchMesas = async () => {
    try {
      const data = await restaurantService.getMesas();
      setSectors(data.setores);
    } catch (error) {
      console.error("Error fetching mesas", error);
    } finally {
      setLoading(false);
    }
  };

  const handleOpenComanda = () => {
    setIsDetailOpen(true);
  };

  const getStatusColor = (status: Mesa['status']) => {
    switch (status) {
      case 'LIVRE': return 'bg-emerald-500';
      case 'OCUPADA': return 'bg-orange-500';
      case 'BLOQUEADA': return 'bg-zinc-400';
      default: return 'bg-zinc-200';
    }
  };

  const getStatusBg = (status: Mesa['status']) => {
    switch (status) {
      case 'LIVRE': return 'bg-emerald-50 border-emerald-100';
      case 'OCUPADA': return 'bg-orange-50 border-orange-100';
      case 'BLOQUEADA': return 'bg-zinc-50 border-zinc-100';
      default: return 'bg-white border-zinc-200';
    }
  };

  return (
    <div className="h-full bg-zinc-50 flex flex-col">
      {isDetailOpen && selectedMesa && (
        <ComandaDetail 
          mesa={selectedMesa} 
          onClose={() => setIsDetailOpen(false)} 
          onUpdate={fetchMesas}
        />
      )}
      <div className="p-6 bg-white border-b border-zinc-200 flex items-center justify-between">
        <div className="flex items-center gap-3">
          <div className="bg-indigo-600 p-2 rounded-xl">
            <LayoutGrid className="text-white w-6 h-6" />
          </div>
          <h1 className="text-2xl font-bold text-zinc-900">Mapa de Mesas</h1>
        </div>
        <div className="flex gap-4">
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-emerald-500"></div>
            <span className="text-sm font-medium text-zinc-600">Livre</span>
          </div>
          <div className="flex items-center gap-2">
            <div className="w-3 h-3 rounded-full bg-orange-500"></div>
            <span className="text-sm font-medium text-zinc-600">Ocupada</span>
          </div>
        </div>
      </div>

      <div className="flex-1 overflow-y-auto p-6 space-y-10 custom-scrollbar">
        {loading ? (
          <div className="flex items-center justify-center h-full">
            <Loader2 className="animate-spin text-indigo-600 w-10 h-10" />
          </div>
        ) : (
          sectors.map(sector => (
            <div key={sector.id} className="space-y-4">
              <div className="flex items-center gap-2">
                <h2 className="text-lg font-black text-zinc-900 uppercase tracking-widest">{sector.nome}</h2>
                <div className="h-px flex-1 bg-zinc-200"></div>
                <span className="text-xs font-bold text-zinc-400">{sector.mesas.length} mesas</span>
              </div>
              
              <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-4">
                {sector.mesas.map(mesa => (
                  <motion.button
                    whileTap={{ scale: 0.95 }}
                    key={mesa.id}
                    onClick={() => setSelectedMesa(mesa)}
                    className={cn(
                      "relative p-5 rounded-3xl border-2 transition-all flex flex-col items-center justify-center gap-2 h-32",
                      getStatusBg(mesa.status),
                      mesa.status === 'OCUPADA' ? 'shadow-lg shadow-orange-100' : 'shadow-sm'
                    )}
                  >
                    <div className={cn("absolute top-3 right-3 w-3 h-3 rounded-full", getStatusColor(mesa.status))}></div>
                    <span className="text-3xl font-black text-zinc-900">#{mesa.numero}</span>
                    <div className="flex items-center gap-1 text-zinc-500">
                      <Users className="w-4 h-4" />
                      <span className="text-xs font-bold">{mesa.capacidade}</span>
                    </div>
                  </motion.button>
                ))}
              </div>
            </div>
          ))
        )}
      </div>

      {/* Mesa Detail Modal (Simplified) */}
      {selectedMesa && !isDetailOpen && (
        <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-end sm:items-center justify-center z-50 p-4">
          <motion.div 
            initial={{ y: 100 }}
            animate={{ y: 0 }}
            className="bg-white w-full max-w-lg rounded-t-[40px] sm:rounded-[40px] overflow-hidden shadow-2xl"
          >
            <div className="p-8">
              <div className="flex justify-between items-start mb-8">
                <div>
                  <h3 className="text-4xl font-black text-zinc-900">Mesa {selectedMesa.numero}</h3>
                  <p className="text-zinc-500 font-medium">Capacidade para {selectedMesa.capacidade} pessoas</p>
                </div>
                <div className={cn("px-4 py-2 rounded-2xl text-white font-bold text-sm", getStatusColor(selectedMesa.status))}>
                  {selectedMesa.status}
                </div>
              </div>

              <div className="space-y-4">
                {selectedMesa.status === 'LIVRE' ? (
                  <button 
                    onClick={handleOpenComanda}
                    className="w-full bg-indigo-600 text-white font-bold py-5 rounded-3xl text-xl shadow-xl shadow-indigo-100 flex items-center justify-center gap-3"
                  >
                    Abrir Comanda <ChevronRight />
                  </button>
                ) : (
                  <>
                    <button 
                      onClick={handleOpenComanda}
                      className="w-full bg-orange-500 text-white font-bold py-5 rounded-3xl text-xl shadow-xl shadow-orange-100 flex items-center justify-center gap-3"
                    >
                      Ver Itens <List />
                    </button>
                    <button className="w-full bg-emerald-600 text-white font-bold py-5 rounded-3xl text-xl shadow-xl shadow-emerald-100 flex items-center justify-center gap-3">
                      Finalizar Pagamento
                    </button>
                  </>
                )}
                <button 
                  onClick={() => setSelectedMesa(null)}
                  className="w-full bg-zinc-100 text-zinc-600 font-bold py-4 rounded-3xl"
                >
                  Voltar
                </button>
              </div>
            </div>
          </motion.div>
        </div>
      )}
    </div>
  );
};
