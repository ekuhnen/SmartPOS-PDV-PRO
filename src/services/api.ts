import axios from 'axios';

const BASE_URL = 'https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1';

const api = axios.create({
  baseURL: BASE_URL,
});

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('access_token');
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

export const authService = {
  login: async (email: string, password: string) => {
    const response = await api.post('/auth-login', { email, password });
    return response.data;
  },
  getPermissions: async () => {
    const response = await api.get('/user-permissions');
    return response.data;
  },
};

export const catalogService = {
  getCatalogs: async (catalogId?: string) => {
    const params = catalogId ? { catalog_id: catalogId } : {};
    const response = await api.get('/api-catalogs', { params });
    return response.data;
  },
};

export const restaurantService = {
  getMesas: async () => {
    const response = await api.get('/api-mesas');
    return response.data;
  },
  manageComanda: async (action: string, data: any) => {
    const response = await api.post('/api-comandas', { action, ...data });
    return response.data;
  },
};

export const salesService = {
  registerSale: async (data: any) => {
    const response = await api.post('/api-vendas', data);
    return response.data;
  },
  getHistory: async (date?: string) => {
    const params = date ? { date } : {};
    const response = await api.get('/api-vendas', { params });
    return response.data;
  },
};

export const cashierService = {
  getHistory: async (date?: string, tipo?: string) => {
    const params: any = {};
    if (date) params.date = date;
    if (tipo) params.tipo = tipo;
    const response = await api.get('/api-caixa', { params });
    return response.data;
  },
  operate: async (action: string, data: any) => {
    const response = await api.post('/api-caixa', { action, ...data });
    return response.data;
  },
};

export default api;
