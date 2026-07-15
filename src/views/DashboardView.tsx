import React, { useState, useEffect } from 'react';
import { salesService } from '../services/api';
import { BarChart3, Calendar, Download, Search, Loader2, ArrowUpRight, ArrowDownRight } from 'lucide-react';
import { formatCurrency } from '../lib/utils';
import { motion } from 'motion/react';

export const DashboardView: React.FC = () => {
  const [sales, setSales] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [date, setDate] = useState(new Date().toISOString().split('T')[0]);

  useEffect(() => {
    fetchSales();
  }, [date]);

  const fetchSales = async () => {
    setLoading(true);
    try {
      const data = await salesService.getHistory(date);
      setSales(data.sales || []);
    } catch (error) {
      console.error("Error fetching sales history", error);
    } finally {
      setLoading(false);
    }
  };

  const totalRevenue = sales.reduce((acc, s) => acc + s.total, 0);

  return (
    <div className="h-full bg-zinc-50 flex flex-col p-6 overflow-hidden">
      <div className="flex items-center justify-between mb-8">
        <div className="flex items-center gap-3">
          <div className="bg-zinc-900 p-3 rounded-2xl shadow-lg">
            <BarChart3 className="text-white w-6 h-6" />
          </div>
          <h1 className="text-2xl font-bold text-zinc-900">Dashboard de Vendas</h1>
        </div>
        <div className="flex gap-3">
          <div className="relative">
            <Calendar className="absolute left-4 top-1/2 -translate-y-1/2 text-zinc-400" size={18} />
            <input 
              type="date" 
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="pl-12 pr-4 py-3 bg-white rounded-xl border border-zinc-200 font-bold text-zinc-600 outline-none focus:ring-2 focus:ring-zinc-900"
            />
          </div>
          <button className="bg-white border border-zinc-200 p-3 rounded-xl hover:bg-zinc-50 transition-colors">
            <Download size={20} className="text-zinc-600" />
          </button>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-4 gap-6 mb-8">
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-xs font-bold uppercase mb-1">Receita Total</p>
          <div className="flex items-end justify-between">
            <h2 className="text-2xl font-black text-zinc-900">{formatCurrency(totalRevenue)}</h2>
            <span className="text-emerald-500 text-xs font-bold flex items-center gap-1 bg-emerald-50 px-2 py-1 rounded-lg">
              <ArrowUpRight size={14} /> +12%
            </span>
          </div>
        </div>
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-xs font-bold uppercase mb-1">Ticket Médio</p>
          <h2 className="text-2xl font-black text-zinc-900">{formatCurrency(sales.length ? totalRevenue / sales.length : 0)}</h2>
        </div>
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-xs font-bold uppercase mb-1">Total Pedidos</p>
          <h2 className="text-2xl font-black text-zinc-900">{sales.length}</h2>
        </div>
        <div className="bg-white p-6 rounded-3xl shadow-sm border border-zinc-200">
          <p className="text-zinc-500 text-xs font-bold uppercase mb-1">Novos Clientes</p>
          <h2 className="text-2xl font-black text-zinc-900">24</h2>
        </div>
      </div>

      <div className="flex-1 bg-white rounded-[40px] shadow-sm border border-zinc-200 flex flex-col overflow-hidden">
        <div className="p-6 border-b border-zinc-100 flex items-center justify-between">
          <h3 className="font-bold text-zinc-900">Histórico de Vendas</h3>
          <div className="relative">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 text-zinc-400" size={16} />
            <input 
              type="text" 
              placeholder="Filtrar..."
              className="pl-10 pr-4 py-2 bg-zinc-50 rounded-lg border-none text-sm outline-none"
            />
          </div>
        </div>
        <div className="flex-1 overflow-y-auto custom-scrollbar">
          {loading ? (
            <div className="flex items-center justify-center h-full"><Loader2 className="animate-spin text-zinc-900" /></div>
          ) : (
            <table className="w-full text-left">
              <thead className="bg-zinc-50 sticky top-0">
                <tr>
                  <th className="px-6 py-4 text-xs font-bold text-zinc-400 uppercase">ID</th>
                  <th className="px-6 py-4 text-xs font-bold text-zinc-400 uppercase">Cliente</th>
                  <th className="px-6 py-4 text-xs font-bold text-zinc-400 uppercase">Status</th>
                  <th className="px-6 py-4 text-xs font-bold text-zinc-400 uppercase">Data</th>
                  <th className="px-6 py-4 text-xs font-bold text-zinc-400 uppercase text-right">Total</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-zinc-100">
                {sales.map((sale) => (
                  <tr key={sale.id} className="hover:bg-zinc-50 transition-colors">
                    <td className="px-6 py-4 text-sm font-mono text-zinc-400">#{sale.id.slice(0, 8)}</td>
                    <td className="px-6 py-4 text-sm font-bold text-zinc-900">{sale.customer_name}</td>
                    <td className="px-6 py-4">
                      <span className="bg-emerald-100 text-emerald-700 px-2 py-1 rounded-lg text-[10px] font-black uppercase">
                        {sale.status}
                      </span>
                    </td>
                    <td className="px-6 py-4 text-sm text-zinc-500">{new Date(sale.created_at).toLocaleTimeString()}</td>
                    <td className="px-6 py-4 text-sm font-black text-zinc-900 text-right">{formatCurrency(sale.total)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      </div>
    </div>
  );
};
