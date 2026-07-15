import React, { useState, useEffect } from 'react';
import { catalogService, restaurantService } from '../services/api';
import { Product, Catalog, Mesa } from '../types';
import { Search, Plus, Minus, X, ShoppingCart, Loader2, CheckCircle2 } from 'lucide-react';
import { formatCurrency } from '../lib/utils';
import { motion, AnimatePresence } from 'motion/react';

interface ComandaDetailProps {
  mesa: Mesa;
  onClose: () => void;
  onUpdate: () => void;
}

export const ComandaDetail: React.FC<ComandaDetailProps> = ({ mesa, onClose, onUpdate }) => {
  const [catalogs, setCatalogs] = useState<Catalog[]>([]);
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [addingItem, setAddingItem] = useState(false);
  const [comanda, setComanda] = useState<any>(null);

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const [catData, comData] = await Promise.all([
        catalogService.getCatalogs(),
        // Se a mesa estiver ocupada, buscaríamos a comanda ativa aqui
        // Por enquanto, simulamos uma comanda ou buscamos via API se houver ID
        Promise.resolve({ items: [], total: 0 }) 
      ]);
      setCatalogs(catData.catalogs);
      setComanda(comData);
    } catch (error) {
      console.error("Error fetching comanda data", error);
    } finally {
      setLoading(false);
    }
  };

  const allProducts = catalogs.flatMap(c => c.products);
  const filteredProducts = allProducts.filter(p => 
    p.name.toLowerCase().includes(search.toLowerCase())
  );

  const handleAddItem = async (product: Product) => {
    setAddingItem(true);
    try {
      await restaurantService.manageComanda('add_item', {
        comanda_id: 'dummy-id', // No fluxo real, usaríamos o ID da comanda aberta
        produto_id: product.id,
        qtd: 1
      });
      // Atualizar localmente para feedback
      setComanda((prev: any) => ({
        ...prev,
        items: [...prev.items, { ...product, quantity: 1 }],
        total: prev.total + product.selling_price
      }));
    } catch (error) {
      console.error("Error adding item", error);
    } finally {
      setAddingItem(false);
    }
  };

  return (
    <motion.div 
      initial={{ opacity: 0 }}
      animate={{ opacity: 1 }}
      className="fixed inset-0 bg-zinc-100 z-50 flex flex-col"
    >
      {/* Header */}
      <div className="bg-white p-4 border-b border-zinc-200 flex items-center justify-between shadow-sm">
        <div className="flex items-center gap-4">
          <button onClick={onClose} className="p-2 hover:bg-zinc-100 rounded-full">
            <X size={24} className="text-zinc-600" />
          </button>
          <div>
            <h2 className="text-xl font-black text-zinc-900">Mesa {mesa.numero}</h2>
            <p className="text-xs font-bold text-orange-500 uppercase tracking-widest">Em Atendimento</p>
          </div>
        </div>
        <div className="text-right">
          <p className="text-xs font-bold text-zinc-400 uppercase">Total Parcial</p>
          <p className="text-2xl font-black text-indigo-600">{formatCurrency(comanda?.total || 0)}</p>
        </div>
      </div>

      <div className="flex-1 flex overflow-hidden">
        {/* Product Selection */}
        <div className="flex-1 flex flex-col p-4 overflow-hidden border-r border-zinc-200">
          <div className="relative mb-4">
            <Search className="absolute left-4 top-1/2 -translate-y-1/2 text-zinc-400" size={20} />
            <input
              type="text"
              placeholder="Adicionar item à conta..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="w-full pl-12 pr-4 py-4 bg-white rounded-2xl border-none shadow-sm focus:ring-2 focus:ring-indigo-500 outline-none"
            />
          </div>

          <div className="flex-1 overflow-y-auto pr-2 custom-scrollbar">
            <div className="grid grid-cols-2 gap-3">
              {filteredProducts.map(product => (
                <motion.button
                  whileTap={{ scale: 0.95 }}
                  key={product.id}
                  onClick={() => handleAddItem(product)}
                  className="bg-white p-4 rounded-2xl shadow-sm border border-zinc-100 text-left flex flex-col justify-between h-32"
                >
                  <h4 className="font-bold text-zinc-900 text-sm line-clamp-2">{product.name}</h4>
                  <div className="flex justify-between items-end">
                    <span className="font-black text-indigo-600">{formatCurrency(product.selling_price)}</span>
                    <div className="bg-indigo-50 p-1.5 rounded-lg">
                      <Plus size={18} className="text-indigo-600" />
                    </div>
                  </div>
                </motion.button>
              ))}
            </div>
          </div>
        </div>

        {/* Current Items List */}
        <div className="w-80 bg-zinc-50 flex flex-col">
          <div className="p-4 border-b border-zinc-200 bg-white">
            <h3 className="font-bold text-zinc-900 flex items-center gap-2">
              <ShoppingCart size={18} /> Itens da Conta
            </h3>
          </div>
          <div className="flex-1 overflow-y-auto p-4 space-y-3 custom-scrollbar">
            {comanda?.items.length === 0 ? (
              <div className="h-full flex flex-col items-center justify-center text-zinc-400 opacity-50 text-center p-4">
                <p className="text-sm">Nenhum item lançado ainda.</p>
              </div>
            ) : (
              comanda?.items.map((item: any, idx: number) => (
                <div key={idx} className="bg-white p-3 rounded-xl shadow-sm border border-zinc-100 flex justify-between items-center">
                  <div>
                    <p className="font-bold text-zinc-900 text-xs">{item.name}</p>
                    <p className="text-[10px] text-zinc-500">{item.quantity}x {formatCurrency(item.selling_price)}</p>
                  </div>
                  <p className="font-bold text-zinc-900 text-sm">{formatCurrency(item.selling_price * item.quantity)}</p>
                </div>
              ))
            )}
          </div>
          <div className="p-4 bg-white border-t border-zinc-200">
            <button className="w-full bg-emerald-600 text-white font-bold py-4 rounded-2xl shadow-lg shadow-emerald-100 mb-2">
              Fechar Conta
            </button>
            <button className="w-full bg-zinc-100 text-zinc-600 font-bold py-3 rounded-2xl text-sm">
              Imprimir Parcial
            </button>
          </div>
        </div>
      </div>

      {addingItem && (
        <div className="fixed bottom-8 left-1/2 -translate-x-1/2 bg-zinc-900 text-white px-6 py-3 rounded-full shadow-2xl flex items-center gap-3 z-[60]">
          <Loader2 className="animate-spin" size={18} />
          <span className="font-bold text-sm">Lançando item...</span>
        </div>
      )}
    </motion.div>
  );
};
