export interface User {
  id: string;
  email: string;
  full_name: string;
}

export interface AuthResponse {
  access_token: string;
  refresh_token: string;
  expires_in: number;
  token_type: string;
  user: User;
}

export interface Permission {
  module: string;
  enabled: boolean;
}

export interface UserPermissions {
  user_id: string;
  company_user_id: string;
  name: string;
  email: string;
  role: 'owner' | 'sub_user';
  active: boolean;
  must_change_password: boolean;
  permissions: Permission[];
}

export interface Product {
  id: string;
  name: string;
  sku: string;
  category: string;
  selling_price: number;
  stock: number;
  group: {
    id: string;
    name: string;
  };
}

export interface Catalog {
  catalog: {
    id: string;
    name: string;
    description: string;
  };
  total_products: number;
  products: Product[];
}

export interface Mesa {
  id: string;
  numero: number;
  capacidade: number;
  status: 'LIVRE' | 'OCUPADA' | 'BLOQUEADA';
}

export interface Sector {
  id: string;
  nome: string;
  descricao: string;
  mesas: Mesa[];
}

export interface Comanda {
  id: string;
  mesa_id?: string;
  garcom_id: string;
  status: 'ABERTA' | 'FECHADA' | 'CANCELADA';
  total: number;
  items: any[];
}

export interface SaleItem {
  product_id: string;
  quantity: number;
  unit_price?: number;
}

export interface SaleRequest {
  customer_name: string;
  customer_id?: string;
  discount: number;
  city: string;
  branch: string;
  items: SaleItem[];
}
