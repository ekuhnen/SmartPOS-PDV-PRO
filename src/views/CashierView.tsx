import React, { useState, useEffect } from 'react';
import { cashierService } from '../services/api';
import { Wallet, ArrowUpCircle, ArrowDownCircle, Lock, Loader2, History, CheckCircle2 } from 'lucide-react';
import { formatCurrency } from '../lib/utils';
import { motion, AnimatePresence } from 'motion/react';

export const CashierView: React.FC = () => {
  const [history, setHistory] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState(false);
  const [showModal, setShowModal] = useState<'abrir' | 'sangria' | 'fechar' | null>(null);
  const [inputValue, setInputValue] = useState('');
  const [obs, setObs] = useState('');

  useEffect(() => {
    fetchHistory();
  }, []);

  const fetchHistory = async () => {
    try {
      const data = await cashierService.getHistory();
      setHistory(data || []);
    } catch (error) {
      console.error("Error fetching cashier history", error);
    } finally {
      setLoading(false);
    }
  };

  const handleAction = async () => {
    if (!showModal) return;
    setActionLoading(true);
    try {
      await cashierService.operate(showModal, {
        valor: parseFloat(inputValue),
        observacao: obs,
        session_id: history.find(h => h.tipo === 'ABERTURA')?.id // Simplificado
      });
      setShowModal(null);
      setInputValue('');
      setObs('');
      fetchHistory();
    } catch (error) {
      alert("Erro ao realizar operação de caixa.");
    } finally {
      setActionLoading(false);
    }
  };

  return (
    <div className="h-full bg-zinc-50 flex flex-col p-6 overflow-hidden">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="bg-orange-600 p-3 rounded-2xl shadow-lg shadow-orange-100">
            <Wallet className="text-white w-6 h-6" />
          </div>
          <h1 className="text-2xl font-bold text-zinc-900">Gestão de Caixa</h1>
        </div>
        <div className="flex gap-3">
          <button 
            onClick={() => setShowModal('abrir')}
            className="bg-emerald-600 text-white px-6 py-3 rounded-xl font-bold flex items-center gap-2 shadow-md"
          >
            <ArrowUpCircle size={20} /> Abrir Caixa
          </button>
          <button 
            onClick={() => setShowModal('sangria')}
            className="bg-orange-500 text-white px-6 py-3 rounded-xl font-bold flex items-center gap-2 shadow-md"
          >
            <ArrowDownCircle size={20} /> Sangria
          </button>
          <button 
            onClick={() => setShowModal('fechar')}
            className="bg-zinc-900 text-white px-6 py-3 rounded-xl font-bold flex items-center gap-2 shadow-md"
          >
            <Lock size={20} /> Fechar
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-sm font-bold uppercase mb-1">Saldo em Dinheiro</p>
          <h2 className="text-3xl font-black text-emerald-600">{formatCurrency(1250.50)}</h2>
        </div>
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-sm font-bold uppercase mb-1">Total Sangrias</p>
          <h2 className="text-3xl font-black text-red-500">{formatCurrency(200.00)}</h2>
        </div>
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-sm font-bold uppercase mb-1">Vendas do Dia</p>
          <h2 className="text-3xl font-black text-indigo-600">{formatCurrency(4580.90)}</h2>
        </div>
      </div>

      <div className="flex-1 bg-white rounded-[40px] shadow-sm border border-zinc-200 flex flex-col overflow-hidden">
        <div className="p-6 border-b border-zinc-100 flex items-center gap-2">
          <History className="text-zinc-400" size={20} />
          <h3 className="font-bold text-zinc-900">Últimas Movimentações</h3>
        </div>
        <div className="flex-1 overflow-y-auto p-4 space-y-3 custom-scrollbar">
          {loading ? (
            <div className="flex items-center justify-center h-full"><Loader2 className="animate-spin text-orange-600" /></div>
          ) : (
            history.map((item, idx) => (
              <div key={idx} className="flex items-center justify-between p-4 bg-zinc-50 rounded-2xl border border-zinc-100">
                <div className="flex items-center gap-4">
                  <div className={cn(
                    "p-2 rounded-xl",
                    item.tipo === 'ABERTURA' ? "bg-emerald-100 text-emerald-600" : 
                    item.tipo === 'SANGRIA' ? "bg-red-100 text-red-600" : "bg-zinc-100 text-zinc-600"
                  )}>
                    {item.tipo === 'ABERTURA' ? <ArrowUpCircle size={20} /> : <ArrowDownCircle size={20} />}
                  </div>
                  <div>
                    <p className="font-bold text-zinc-900">{item.tipo}</p>
                    <p className="text-xs text-zinc-500">{item.observacao || 'Sem observação'}</p>
                  </div>
                </div>
                <div className="text-right">
                  <p className="font-black text-zinc-900">{formatCurrency(item.valor)}</p>
                  <p className="text-[10px] text-zinc-400 uppercase font-bold">10:25 • Operador João</p>
                </div>
              </div>
            ))
          )}
        </div>
      </div>

      {/* Action Modal */}
      <AnimatePresence>
        {showModal && (
          <div className="fixed inset-0 bg-black/60 backdrop-blur-sm flex items-center justify-center z-50 p-4">
            <motion.div 
              initial={{ scale: 0.9, opacity: 0 }}
              animate={{ scale: 1, opacity: 1 }}
              className="bg-white w-full max-w-md rounded-[40px] p-8 shadow-2xl"
            >
              <h3 className="text-2xl font-black text-zinc-900 mb-6 capitalize">
                {showModal === 'abrir' ? 'Abertura de Caixa' : showModal === 'sangria' ? 'Sangria de Caixa' : 'Fechar Caixa'}
              </h3>
              
              <div className="space-y-6">
                <div>
                  <label className="block text-sm font-bold text-zinc-500 uppercase mb-2">Valor (R$)</label>
                  <input
                    type="number"
                    value={inputValue}
                    onChange={(e) => setInputValue(e.target.value)}
                    className="w-full text-4xl font-black p-4 bg-zinc-100 rounded-2xl border-none focus:ring-2 focus:ring-orange-500 outline-none"
                    placeholder="0,00"
                  />
                </div>

                <div>
                  <label className="block text-sm font-bold text-zinc-500 uppercase mb-2">Observação</label>
                  <textarea
                    value={obs}
                    onChange={(e) => setObs(e.target.value)}
                    className="w-full p-4 bg-zinc-100 rounded-2xl border-none focus:ring-2 focus:ring-orange-500 outline-none h-24 resize-none"
                    placeholder="Motivo da operação..."
                  />
                </div>

                <div className="flex gap-3 pt-4">
                  <button 
                    onClick={() => setShowModal(null)}
                    className="flex-1 bg-zinc-100 text-zinc-600 font-bold py-4 rounded-2xl"
                  >
                    Cancelar
                  </button>
                  <button 
                    onClick={handleAction}
                    disabled={actionLoading || !inputValue}
                    className="flex-1 bg-orange-600 text-white font-bold py-4 rounded-2xl shadow-lg shadow-orange-100 flex items-center justify-center"
                  >
                    {actionLoading ? <Loader2 className="animate-spin" /> : 'Confirmar'}
                  </button>
                </div>
              </div>
            </motion.div>
          </div>
        )}
      </AnimatePresence>
    </div>
  );
};

import { cn } from '../lib/utils';
