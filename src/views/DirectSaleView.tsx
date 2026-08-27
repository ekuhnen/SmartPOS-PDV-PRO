import React, { useState, useEffect } from 'react';
import { catalogService, salesService } from '../services/api';
import { Product, Catalog } from '../types';
import { Search, ShoppingCart, Plus, Minus, Trash2, CheckCircle2, Loader2 } from 'lucide-react';
import { formatCurrency } from '../lib/utils';
import { motion, AnimatePresence } from 'motion/react';
import { waitForSifenCdc } from '../lib/waitForSifenCdc';

export const DirectSaleView: React.FC = () => {
  const [catalogs, setCatalogs] = useState<Catalog[]>([]);
  const [search, setSearch] = useState('');
  const [cart, setCart] = useState<{ product: Product; quantity: number }[]>([]);
  const [loading, setLoading] = useState(true);
  const [isCheckingOut, setIsCheckingOut] = useState(false);
  const [showSuccess, setShowSuccess] = useState(false);

  useEffect(() => {
    fetchCatalogs();
  }, []);

  const fetchCatalogs = async () => {
    try {
      const data = await catalogService.getCatalogs();
      setCatalogs(data.catalogs);
    } catch (error) {
      console.error("Error fetching catalogs", error);
    } finally {
      setLoading(false);
    }
  };

  const allProducts = catalogs.flatMap(c => c.products);
  const filteredProducts = allProducts.filter(p => 
    p.name.toLowerCase().includes(search.toLowerCase()) || 
    p.sku.toLowerCase().includes(search.toLowerCase())
  );

  const addToCart = (product: Product) => {
    setCart(prev => {
      const existing = prev.find(item => item.product.id === product.id);
      if (existing) {
        return prev.map(item => 
          item.product.id === product.id 
            ? { ...item, quantity: item.quantity + 1 } 
            : item
        );
      }
      return [...prev, { product, quantity: 1 }];
    });
  };

  const removeFromCart = (productId: string) => {
    setCart(prev => prev.filter(item => item.product.id !== productId));
  };

  const updateQuantity = (productId: string, delta: number) => {
    setCart(prev => prev.map(item => {
      if (item.product.id === productId) {
        const newQty = Math.max(1, item.quantity + delta);
        return { ...item, quantity: newQty };
      }
      return item;
    }));
  };

  const total = cart.reduce((acc, item) => acc + (item.product.selling_price * item.quantity), 0);

  const handleCheckout = async () => {
    if (cart.length === 0) return;
    setIsCheckingOut(true);
    try {
      const response = await salesService.registerSale({
        customer_name: "Consumidor",
        discount: 0,
        city: "São Paulo",
        branch: "Loja Principal",
        items: cart.map(item => ({
          product_id: item.product.id,
          quantity: item.quantity
        }))
      });
      
      // Polling curto para obter o CDC do SIFEN
      if (response && response.id) {
        console.log("Venda concluída, aguardando CDC fiscal do SIFEN...");
        const { cdc, timedOut } = await waitForSifenCdc(response.id);
        if (cdc) {
          console.log("SIFEN CDC obtido no frontend:", cdc);
        } else if (timedOut) {
          console.warn("Timeout ao aguardar SIFEN CDC");
        }
      }

      setShowSuccess(true);
      setCart([]);
      setTimeout(() => setShowSuccess(false), 3000);
    } catch (error) {
      alert("Erro ao finalizar venda.");
    } finally {
      setIsCheckingOut(false);
    }
  };

  return (
    <div className="flex h-full bg-zinc-100 overflow-hidden">
      {/* Product List Section */}
      <div className="flex-1 flex flex-col p-4 overflow-hidden">
        <div className="relative mb-4">
          <Search className="absolute left-4 top-1/2 -translate-y-1/2 text-zinc-400 w-5 h-5" />
          <input
            type="text"
            placeholder="Buscar produto ou SKU..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-full pl-12 pr-4 py-4 bg-white rounded-2xl border-none shadow-sm focus:ring-2 focus:ring-emerald-500 outline-none text-lg"
          />
        </div>

        <div className="flex-1 overflow-y-auto pr-2 custom-scrollbar">
          {loading ? (
            <div className="flex items-center justify-center h-full">
              <Loader2 className="animate-spin text-emerald-600 w-10 h-10" />
            </div>
          ) : (
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-3">
              {filteredProducts.map(product => (
                <motion.button
                  whileTap={{ scale: 0.95 }}
                  key={product.id}
                  onClick={() => addToCart(product)}
                  className="bg-white p-4 rounded-2xl shadow-sm border border-zinc-200 text-left flex flex-col justify-between h-40 hover:border-emerald-500 transition-colors"
                >
                  <div>
                    <span className="text-xs font-bold text-emerald-600 uppercase tracking-wider">{product.category}</span>
                    <h3 className="font-bold text-zinc-900 line-clamp-2 leading-tight mt-1">{product.name}</h3>
                  </div>
                  <div className="flex justify-between items-end">
                    <span className="text-lg font-black text-zinc-900">{formatCurrency(product.selling_price)}</span>
                    <div className="bg-emerald-50 p-2 rounded-xl">
                      <Plus className="text-emerald-600 w-5 h-5" />
                    </div>
                  </div>
                </motion.button>
              ))}
            </div>
          )}
        </div>
      </div>

      {/* Cart Sidebar */}
      <div className="w-96 bg-white border-l border-zinc-200 flex flex-col shadow-2xl">
        <div className="p-6 border-bottom border-zinc-100 flex items-center justify-between">
          <div className="flex items-center gap-2">
            <ShoppingCart className="text-emerald-600 w-6 h-6" />
            <h2 className="text-xl font-bold text-zinc-900">Carrinho</h2>
          </div>
          <span className="bg-emerald-100 text-emerald-700 px-3 py-1 rounded-full text-sm font-bold">
            {cart.length} itens
          </span>
        </div>

        <div className="flex-1 overflow-y-auto p-4 space-y-4">
          <AnimatePresence>
            {cart.map(item => (
              <motion.div
                initial={{ opacity: 0, x: 20 }}
                animate={{ opacity: 1, x: 0 }}
                exit={{ opacity: 0, x: -20 }}
                key={item.product.id}
                className="flex items-center gap-3 p-3 bg-zinc-50 rounded-2xl border border-zinc-100"
              >
                <div className="flex-1">
                  <h4 className="font-bold text-zinc-900 text-sm line-clamp-1">{item.product.name}</h4>
                  <p className="text-emerald-600 font-bold text-sm">{formatCurrency(item.product.selling_price)}</p>
                </div>
                <div className="flex items-center gap-2 bg-white rounded-xl border border-zinc-200 p-1">
                  <button onClick={() => updateQuantity(item.product.id, -1)} className="p-1 hover:bg-zinc-100 rounded-lg">
                    <Minus className="w-4 h-4" />
                  </button>
                  <span className="w-6 text-center font-bold">{item.quantity}</span>
                  <button onClick={() => updateQuantity(item.product.id, 1)} className="p-1 hover:bg-zinc-100 rounded-lg">
                    <Plus className="w-4 h-4" />
                  </button>
                </div>
                <button onClick={() => removeFromCart(item.product.id)} className="text-zinc-400 hover:text-red-500 p-2">
                  <Trash2 className="w-5 h-5" />
                </button>
              </motion.div>
            ))}
          </AnimatePresence>
          {cart.length === 0 && (
            <div className="h-full flex flex-col items-center justify-center text-zinc-400 opacity-50">
              <ShoppingCart className="w-16 h-16 mb-4" />
              <p>Seu carrinho está vazio</p>
            </div>
          )}
        </div>

        <div className="p-6 bg-zinc-50 border-t border-zinc-200">
          <div className="flex justify-between mb-4">
            <span className="text-zinc-500 font-medium">Total</span>
            <span className="text-3xl font-black text-zinc-900">{formatCurrency(total)}</span>
          </div>
          <button
            onClick={handleCheckout}
            disabled={cart.length === 0 || isCheckingOut}
            className="w-full bg-emerald-600 hover:bg-emerald-700 text-white font-bold py-5 rounded-2xl shadow-lg shadow-emerald-100 transition-all flex items-center justify-center disabled:opacity-50 disabled:grayscale"
          >
            {isCheckingOut ? <Loader2 className="animate-spin" /> : 'Finalizar Venda'}
          </button>
        </div>
      </div>

      {/* Success Overlay */}
      <AnimatePresence>
        {showSuccess && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="fixed inset-0 bg-emerald-600/95 flex flex-col items-center justify-center z-50 text-white"
          >
            <motion.div
              initial={{ scale: 0.5, rotate: -10 }}
              animate={{ scale: 1, rotate: 0 }}
              className="bg-white p-8 rounded-full mb-6 shadow-2xl"
            >
              <CheckCircle2 className="text-emerald-600 w-24 h-24" />
            </motion.div>
            <h2 className="text-4xl font-black mb-2">Venda Realizada!</h2>
            <p className="text-emerald-100 text-xl">Imprimindo cupom...</p>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
};
