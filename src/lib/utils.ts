import { clsx, type ClassValue } from 'clsx';
import { twMerge } from 'tailwind-merge';

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

export const formatCurrency = (value: number, currencyCode: string = 'BRL') => {
  const displayDecimals = (currencyCode === 'PYG' || currencyCode === 'ARS') ? 0 : 2;
  return new Intl.NumberFormat('pt-BR', {
    style: 'currency',
    currency: currencyCode,
    minimumFractionDigits: displayDecimals,
    maximumFractionDigits: displayDecimals,
  }).format(value);
};

export const printReceipt = (content: string) => {
  console.log("Simulating Print:", content);
  // In a real SmartPOS, this would call the native bridge
  const printWindow = window.open('', '_blank');
  if (printWindow) {
    printWindow.document.write(`<pre>${content}</pre>`);
    printWindow.document.close();
    printWindow.print();
  }
};
