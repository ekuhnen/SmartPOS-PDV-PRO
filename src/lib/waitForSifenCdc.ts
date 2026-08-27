import { salesService } from '../services/api';

/**
 * Helper para realizar polling curto e aguardar que o backend processe
 * o envio para o SIFEN e grave o sifen_cdc correspondente à venda.
 *
 * Aplica um intervalo com backoff simples e timeout de 15 segundos.
 */
export async function waitForSifenCdc(saleId: string): Promise<{ cdc: string | null; timedOut: boolean }> {
  // Só faz polling se o SIFEN estiver habilitado nas configurações (caso PY)
  const sifenConfigRaw = localStorage.getItem('sifen_config');
  const sifenConfig = sifenConfigRaw ? JSON.parse(sifenConfigRaw) : { enabled: true }; // Enabled por padrão para fins de POS

  if (!sifenConfig || !sifenConfig.enabled) {
    return { cdc: null, timedOut: false };
  }

  const timeoutMs = 15000;
  const startTime = Date.now();
  let delayMs = 500;

  while (Date.now() - startTime < timeoutMs) {
    try {
      const sale = await salesService.getSaleById(saleId);
      if (sale && sale.sifen_cdc) {
        return { cdc: sale.sifen_cdc, timedOut: false };
      }
    } catch (error) {
      console.error('Erro no polling do SIFEN CDC:', error);
    }
    
    await new Promise((resolve) => setTimeout(resolve, delayMs));
    // Backoff simples: aumenta o intervalo gradativamente (500ms -> 750ms -> 1125ms -> ... max 3s)
    delayMs = Math.min(delayMs * 1.5, 3000);
  }

  return { cdc: null, timedOut: true };
}
